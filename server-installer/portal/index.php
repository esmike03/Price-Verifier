<?php
declare(strict_types=1);

const CONFIG_FILE = __DIR__ . '/upload-config.php';
const SCHEDULE_MEDIA_INDEX_FILE = __DIR__ . '/scheduled-media.txt';
const APP_UPDATE_INFO_FILE = __DIR__ . '/app-update-info.json';
const MEDIA_ORDER_FILE = __DIR__ . '/media-order.txt';
const CATALOG_PAGE_SIZE = 50;
// Extra catalog header names added in the portal (Catalog > Column names).
const CATALOG_COLUMNS_FILE = __DIR__ . '/catalog-columns.json';
// The current "sync now" request and what each scanner last reported. A .php
// file that exits at once, so no web server ever serves its contents.
const DEVICE_SYNC_FILE = __DIR__ . '/device-sync.php';
// An older scanner app checks in once a minute and does not say so: it counts
// as online when it has checked in within this many seconds. Newer apps report
// their own interval (a few seconds), see deviceOnlineSeconds().
const DEVICE_ONLINE_SECONDS = 150;
// What a "sync now" request can ask for, and the name shown for each.
const DEVICE_SYNC_SCOPES = [
    'items' => 'Sync items',
    'images' => 'Sync images',
    'videos' => 'Sync videos',
    'app' => 'App update',
    'all' => 'Sync everything',
];
// The requests every app version with force sync understands; the others need
// an app that reports its check-in interval.
const DEVICE_SYNC_BASIC_SCOPES = ['items', 'all'];
// Data source tab: reading the catalog from the store's POS database. The
// settings hold the database password, so like device-sync.php they are .php
// files that exit at once and are never served as text.
const SOURCE_CONFIG_FILE = __DIR__ . '/catalog-source.php';
const SOURCE_STATE_FILE = __DIR__ . '/catalog-source-state.php';
const SOURCE_LOCK_FILE = __DIR__ . '/.catalog-source.lock';
// Touched each time the scheduler checks in, so the portal can tell when it is not running.
const SOURCE_TICK_FILE = __DIR__ . '/.catalog-source.tick';
const SOURCE_MAX_ROWS = 1000000;

// Scanners check in every few seconds; answer before a session is started so
// those requests do not each leave a session file behind.
if (isset($_GET['device_poll'])) {
    outputDevicePoll();
}

// The server's scheduler (Windows supervisor, Linux systemd timer, or a cron
// job on other hosting) runs "php index.php --catalog-source-sync" about every
// minute. Only the command line gets here; a web request never does.
if (PHP_SAPI === 'cli' && in_array('--catalog-source-sync', $argv ?? [], true)) {
    exit(catalogSourceCli($argv));
}

$isHttps = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
    || strtolower((string) ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '')) === 'https';
session_set_cookie_params([
    'httponly' => true,
    'secure' => $isHttps,
    'samesite' => 'Lax',
]);
session_start();

function h(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES, 'UTF-8');
}

function csrfToken(): string
{
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(24));
    }
    return $_SESSION['csrf'];
}

function requireCsrf(): void
{
    $sent = isset($_POST['csrf']) ? (string) $_POST['csrf'] : '';
    if (!hash_equals(csrfToken(), $sent)) {
        throw new RuntimeException('Your session expired. Please try again.');
    }
}

function setFlash(string $message, string $type = 'success'): void
{
    $_SESSION['flash'] = ['message' => $message, 'type' => $type];
}

function takeFlash(): ?array
{
    $flash = isset($_SESSION['flash']) && is_array($_SESSION['flash'])
        ? $_SESSION['flash']
        : null;
    unset($_SESSION['flash']);
    return $flash;
}

function redirectHome(): void
{
    $path = isset($_SERVER['PHP_SELF']) ? (string) $_SERVER['PHP_SELF'] : '/';
    header('Location: ' . $path);
    exit;
}

function appUpdateInfo(): array
{
    if (!is_file(APP_UPDATE_INFO_FILE)) {
        return ['version' => '', 'changes' => ''];
    }
    $json = json_decode((string) file_get_contents(APP_UPDATE_INFO_FILE), true);
    if (!is_array($json)) {
        return ['version' => '', 'changes' => ''];
    }
    return [
        'version' => cleanUpdateText((string) ($json['version'] ?? ''), 80),
        'changes' => cleanUpdateText((string) ($json['changes'] ?? ''), 3000),
    ];
}

function cleanUpdateText(string $value, int $max): string
{
    $value = trim(str_replace("\r\n", "\n", $value));
    $value = preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/', '', $value) ?? '';
    return substr($value, 0, $max);
}

/** One line of text: the scanner's scrolling bar shows a single line. */
function cleanMarqueeText(string $value): string
{
    $value = preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/', '', $value) ?? '';
    $oneLine = preg_replace('/\s+/u', ' ', $value);
    if ($oneLine === null) {
        $oneLine = (string) preg_replace('/\s+/', ' ', $value);    // not valid UTF-8
    }
    $oneLine = trim($oneLine);
    return function_exists('mb_substr') ? mb_substr($oneLine, 0, 2000, 'UTF-8') : substr($oneLine, 0, 2000);
}

/** The scrolling message scanners will show, or '' when there is none. */
function marqueeText(): string
{
    $path = __DIR__ . '/marquee.txt';
    return is_file($path) ? cleanMarqueeText((string) file_get_contents($path, false, null, 0, 262144)) : '';
}

/**
 * Saves the scrolling message typed in the portal. An empty message is kept as
 * an empty marquee.txt on purpose: a scanner keeps its old text when the file
 * is missing, so the empty file is what tells it to hide the scrolling bar.
 */
function saveMarqueeText(string $text): string
{
    $clean = cleanMarqueeText($text);
    $path = __DIR__ . '/marquee.txt';
    if (file_put_contents($path, $clean === '' ? '' : $clean . "\n", LOCK_EX) === false) {
        throw new RuntimeException('The scrolling message could not be saved.');
    }
    @chmod($path, 0644);
    return $clean;
}

function saveAppUpdateInfo(string $version, string $changes): void
{
    $data = [
        'version' => cleanUpdateText($version, 80),
        'changes' => cleanUpdateText($changes, 3000),
    ];
    $json = json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    if ($json === false || file_put_contents(APP_UPDATE_INFO_FILE, $json . "\n", LOCK_EX) === false) {
        throw new RuntimeException('The server could not save the app update details.');
    }
    @chmod(APP_UPDATE_INFO_FILE, 0640);
}

function isDisplayMediaName(string $name): bool
{
    return preg_match('/^(?:[A-Z0-9]{3}_)?\d{6}(?:_\d{1,4})?\.(?:jpg|jpeg|png|mp4)$/i', $name) === 1;
}

/**
 * The last day an upload scheduled today should play: the same day next month,
 * or that month's last day when it is shorter (January 31 -> February 28).
 */
function defaultScheduleEnd(?DateTimeImmutable $today = null): DateTimeImmutable
{
    $today = $today ?? new DateTimeImmutable('today');
    $nextMonth = $today->modify('first day of next month');
    $day = min((int) $today->format('j'), (int) $nextMonth->format('t'));
    return $nextMonth->setDate((int) $nextMonth->format('Y'), (int) $nextMonth->format('n'), $day);
}

/**
 * The scheduled name given to an upload that is not already named with an end
 * date: MMDDYY.ext for one month from today, with a 3-digit code in front
 * (001_MMDDYY.ext) when that name is already taken.
 */
function autoScheduleMediaName(string $extension): string
{
    $date = defaultScheduleEnd()->format('mdy');
    $extension = strtolower($extension);
    for ($code = 0; $code <= 999; $code++) {
        $name = ($code === 0 ? '' : sprintf('%03d_', $code)) . $date . '.' . $extension;
        if (!is_file(__DIR__ . '/' . $name)) {
            return $name;
        }
    }
    throw new RuntimeException('Too many scheduled files already end on that date. Remove some and upload again.');
}

function isSupportedScheduleMediaName(string $name): bool
{
    if ($name === '' || strlen($name) > 180 || $name[0] === '.'
        || basename(str_replace('\\', '/', $name)) !== $name
        || strpos($name, ',') !== false
        || preg_match('/[\x00-\x1F\x7F]/', $name) === 1) {
        return false;
    }
    $reserved = ['logo.png', 'logo.jpg', 'banner.jpg', 'banner.png', 'video.mp4'];
    if (in_array(strtolower($name), $reserved, true)) {
        return false;
    }
    return in_array(strtolower(pathinfo($name, PATHINFO_EXTENSION)),
        ['jpg', 'jpeg', 'png', 'mp4'], true);
}

function scheduledMediaRegistry(): array
{
    if (!is_file(SCHEDULE_MEDIA_INDEX_FILE)) {
        return [];
    }
    $files = [];
    foreach (file(SCHEDULE_MEDIA_INDEX_FILE, FILE_IGNORE_NEW_LINES) ?: [] as $line) {
        $name = trim((string) $line);
        if (isSupportedScheduleMediaName($name)) {
            $files[$name] = true;
        }
    }
    return array_keys($files);
}

function saveScheduledMediaRegistry(array $names): void
{
    $safe = [];
    foreach ($names as $name) {
        if (is_string($name) && isSupportedScheduleMediaName($name)) {
            $safe[$name] = true;
        }
    }
    $content = implode("\n", array_keys($safe));
    if ($content !== '') {
        $content .= "\n";
    }
    if (file_put_contents(SCHEDULE_MEDIA_INDEX_FILE, $content, LOCK_EX) === false) {
        throw new RuntimeException('The server could not update the scheduled media list.');
    }
    @chmod(SCHEDULE_MEDIA_INDEX_FILE, 0640);
}

function registerScheduledMedia(string $name): void
{
    $names = scheduledMediaRegistry();
    $names[] = $name;
    saveScheduledMediaRegistry($names);
}

function unregisterScheduledMedia(string $name): void
{
    saveScheduledMediaRegistry(array_values(array_filter(scheduledMediaRegistry(),
        static fn(string $registered): bool => $registered !== $name)));
}

function scheduleMediaFiles(bool $validOnly = false): array
{
    $files = [];
    $registered = array_fill_keys(scheduledMediaRegistry(), true);
    foreach (scandir(__DIR__) ?: [] as $name) {
        if (!is_string($name) || !isSupportedScheduleMediaName($name)
            || (!isDisplayMediaName($name) && !isset($registered[$name]))
            || ($validOnly && !isDisplayMediaName($name))) {
            continue;
        }
        $path = __DIR__ . '/' . $name;
        if (is_file($path)) {
            $files[] = [
                'name' => $name,
                'size' => filesize($path) ?: 0,
                'time' => filemtime($path) ?: time(),
            ];
        }
    }
    usort($files, static function (array $a, array $b): int {
        return strcasecmp($a['name'], $b['name']);
    });
    return $files;
}

function displayMediaFiles(): array
{
    return scheduleMediaFiles(true);
}

function outputMediaManifest(): void
{
    header('Content-Type: text/plain; charset=utf-8');
    echo "# price-verifier-media-manifest\n";
    foreach (scheduleMediaFiles() as $file) {
        echo $file['name'] . ',' . (int) $file['size'] . ',' . (int) $file['time'] . "\n";
    }
    exit;
}

if (isset($_GET['media_manifest'])) {
    outputMediaManifest();
}

/**
 * Every idle-display item that currently exists on the server, expressed as an
 * ordering token: 'banner' for the banner image, 'video' for the idle video,
 * and the file name for each scheduled display-media clip. This is the full set
 * of things the playback order can arrange.
 */
function currentIdleTokens(): array
{
    $tokens = [];
    if (is_file(__DIR__ . '/banner.jpg') || is_file(__DIR__ . '/banner.png')) {
        $tokens[] = 'banner';
    }
    if (is_file(__DIR__ . '/video.mp4')) {
        $tokens[] = 'video';
    }
    foreach (scheduleMediaFiles() as $file) {
        $tokens[] = $file['name'];
    }
    return $tokens;
}

function storedMediaOrder(): array
{
    if (!is_file(MEDIA_ORDER_FILE)) {
        return [];
    }
    $tokens = [];
    foreach (file(MEDIA_ORDER_FILE, FILE_IGNORE_NEW_LINES) ?: [] as $line) {
        $token = trim((string) $line);
        if ($token !== '' && $token[0] !== '#') {
            $tokens[] = $token;
        }
    }
    return $tokens;
}

/**
 * The stored order intersected with what currently exists (dropping stale
 * entries and keeping their saved sequence), followed by any present item that
 * has never been ordered so new uploads still appear.
 */
function effectiveMediaOrder(): array
{
    $current = currentIdleTokens();
    $present = array_fill_keys($current, true);
    $ordered = [];
    $seen = [];
    foreach (storedMediaOrder() as $token) {
        if (isset($present[$token]) && !isset($seen[$token])) {
            $ordered[] = $token;
            $seen[$token] = true;
        }
    }
    foreach ($current as $token) {
        if (!isset($seen[$token])) {
            $ordered[] = $token;
            $seen[$token] = true;
        }
    }
    return $ordered;
}

function saveMediaOrder(array $tokens): void
{
    $present = array_fill_keys(currentIdleTokens(), true);
    $safe = [];
    $seen = [];
    foreach ($tokens as $token) {
        $token = is_string($token) ? trim($token) : '';
        if ($token !== '' && isset($present[$token]) && !isset($seen[$token])) {
            $safe[] = $token;
            $seen[$token] = true;
        }
    }
    $content = $safe ? implode("\n", $safe) . "\n" : '';
    if (file_put_contents(MEDIA_ORDER_FILE, $content, LOCK_EX) === false) {
        throw new RuntimeException('The server could not save the playback order.');
    }
    @chmod(MEDIA_ORDER_FILE, 0640);
}

function idleTokenLabel(string $token): string
{
    if ($token === 'banner') {
        return 'Banner image';
    }
    if ($token === 'video') {
        return 'Idle video (video.mp4)';
    }
    return $token;
}

/**
 * A short badge for the playback list: what kind of thing this token plays.
 */
function idleTokenKind(string $token): string
{
    if ($token === 'banner') {
        return 'Banner';
    }
    if ($token === 'video') {
        return 'Idle video';
    }
    return strtolower(pathinfo($token, PATHINFO_EXTENSION)) === 'mp4' ? 'Video' : 'Image';
}

/** Cache-busted URL for a bundled asset. */
function asset(string $relative): string
{
    $path = __DIR__ . '/' . $relative;
    $stamp = is_file($path) ? (string) filemtime($path) : '1';
    return $relative . '?v=' . $stamp;
}

/** Inline SVG icons, stroke-based so they inherit the surrounding text colour. */
function icon(string $name): string
{
    $paths = [
        'tag' => '<path d="M3 11V5a2 2 0 0 1 2-2h6l10 10-8 8L3 11Z"/><circle cx="7.5" cy="7.5" r="1.5"/>',
        'list' => '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
        'phone' => '<rect x="6" y="2" width="12" height="20" rx="2"/><path d="M11 18h2"/>',
        'image' => '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9.5" r="1.5"/><path d="m21 16-5-5L5 20"/>',
        'banner' => '<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M2 14h20"/>',
        'film' => '<rect x="2" y="4" width="20" height="16" rx="2"/><path d="M7 4v16M17 4v16M2 12h20M2 8h5M2 16h5M17 8h5M17 16h5"/>',
        'video' => '<rect x="2" y="5" width="14" height="14" rx="2"/><path d="m22 8-6 4 6 4V8Z"/>',
        'text' => '<path d="M4 7V5h16v2M9 5v14M15 19H9"/>',
        'search' => '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
        'grip' => '<circle cx="9" cy="6" r="1.4"/><circle cx="15" cy="6" r="1.4"/><circle cx="9" cy="12" r="1.4"/><circle cx="15" cy="12" r="1.4"/><circle cx="9" cy="18" r="1.4"/><circle cx="15" cy="18" r="1.4"/>',
        'chevron' => '<path d="m9 6 6 6-6 6"/>',
        'lock' => '<rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
        'sun' => '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
        'moon' => '<path d="M21 13A9 9 0 1 1 11 3a7 7 0 0 0 10 10Z"/>',
        'download' => '<path d="M12 3v12M7 11l5 5 5-5M4 20h16"/>',
        'close' => '<path d="m6 6 12 12M18 6 6 18"/>',
        'edit' => '<path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4Z"/><path d="m13.5 6.5 4 4"/>',
        'plus' => '<path d="M12 5v14M5 12h14"/>',
        'chevron-left' => '<path d="m15 6-6 6 6 6"/>',
        'box' => '<path d="m21 8-9-5-9 5 9 5 9-5Z"/><path d="M3 8v8l9 5 9-5V8M12 13v8"/>',
        'database' => '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v7c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12v7c0 1.7 3.6 3 8 3s8-1.3 8-3v-7"/>',
        'palette' => '<path d="M12 3a9 9 0 1 0 0 18c1.1 0 1.5-.8 1.5-1.5 0-.9-.7-1.2-.7-2 0-.8.7-1.5 1.5-1.5H17a4 4 0 0 0 4-4c0-5-4-9-9-9Z"/><circle cx="7.5" cy="11" r="1.2"/><circle cx="10" cy="7" r="1.2"/><circle cx="15" cy="7.5" r="1.2"/>',
    ];
    $body = $paths[$name] ?? $paths['tag'];
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" '
        . 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
        . $body . '</svg>';
}

function slotIcon(string $slotKey): string
{
    $map = [
        'catalog' => 'list',
        'app' => 'phone',
        'logo' => 'image',
        'banner' => 'banner',
        'display_media' => 'film',
        'video' => 'video',
        'marquee' => 'text',
    ];
    return icon($map[$slotKey] ?? 'tag');
}

function outputMediaOrder(): void
{
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: no-store');
    echo "# price-verifier-media-order\n";
    foreach (effectiveMediaOrder() as $token) {
        echo $token . "\n";
    }
    exit;
}

if (isset($_GET['media_order'])) {
    outputMediaOrder();
}

function absoluteServerUrl(string $fileName): string
{
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || strtolower((string) ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '')) === 'https';
    $scheme = $https ? 'https' : 'http';
    $host = isset($_SERVER['HTTP_HOST']) ? (string) $_SERVER['HTTP_HOST'] : '';
    $script = isset($_SERVER['SCRIPT_NAME']) ? (string) $_SERVER['SCRIPT_NAME'] : '/index.php';
    $base = rtrim(str_replace('\\', '/', dirname($script)), '/');
    if ($base === '' || $base === '.') {
        $base = '';
    }
    return $scheme . '://' . $host . $base . '/' . rawurlencode($fileName);
}

/** ['request' => the current "sync now" request or null, 'devices' => [serial => last report]]. */
function deviceSyncDecode(string $raw): array
{
    $json = json_decode((string) preg_replace('/^<\?php[^\n]*\n/', '', $raw), true);
    $state = is_array($json) ? $json : [];
    return [
        'request' => isset($state['request']) && is_array($state['request']) ? $state['request'] : null,
        'devices' => isset($state['devices']) && is_array($state['devices']) ? $state['devices'] : [],
    ];
}

function deviceSyncState(): array
{
    $handle = is_file(DEVICE_SYNC_FILE) ? @fopen(DEVICE_SYNC_FILE, 'rb') : false;
    if ($handle === false) {
        return deviceSyncDecode('');
    }
    try {
        flock($handle, LOCK_SH);
        return deviceSyncDecode((string) stream_get_contents($handle));
    } finally {
        flock($handle, LOCK_UN);
        fclose($handle);
    }
}

/** Reads, changes and saves the state under an exclusive lock; $change gets it by reference. */
function deviceSyncUpdate(callable $change): array
{
    $handle = fopen(DEVICE_SYNC_FILE, 'c+b');
    if ($handle === false) {
        throw new RuntimeException('The scanner status file could not be opened.');
    }
    try {
        flock($handle, LOCK_EX);
        $state = deviceSyncDecode((string) stream_get_contents($handle));
        $change($state);
        $json = json_encode($state, JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
        rewind($handle);
        ftruncate($handle, 0);
        fwrite($handle, "<?php exit; ?>\n" . $json . "\n");
        fflush($handle);
        return $state;
    } finally {
        flock($handle, LOCK_UN);
        fclose($handle);
    }
}

/**
 * Seconds without a check-in after which a scanner counts as offline: three
 * missed check-ins for an app that reports its interval, so one lost request
 * does not flip it.
 */
function deviceOnlineSeconds(array $device): int
{
    $every = (int) ($device['every'] ?? 0);
    return $every > 0 ? max(6, $every * 3) : DEVICE_ONLINE_SECONDS;
}

/** Whether this scanner's app can carry out a request of the given kind. */
function deviceUnderstandsScope(array $device, string $scope): bool
{
    return in_array($scope, DEVICE_SYNC_BASIC_SCOPES, true) || (int) ($device['every'] ?? 0) > 0;
}

/**
 * A scanner's check-in (every few seconds, no sign-in): records that it is
 * online and what it reports, and tells it whether a sync was requested.
 *   ?device_poll=1&serial=U26F00804&app=v6&every=2    -> {"request":{"id":..,"scope":"items","age":12}}
 *   ...&done=<request id>&ok=1&message=...            -> the scanner finished that request
 * "every" is the app's check-in interval in seconds; older apps leave it out.
 */
function outputDevicePoll(): void
{
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    $serial = strtoupper(trim((string) ($_GET['serial'] ?? '')));
    if (!preg_match('/^[A-Z0-9._:-]{3,40}$/', $serial)) {
        http_response_code(400);
        echo json_encode(['error' => 'serial']);
        exit;
    }
    $app = substr((string) preg_replace('/[^A-Za-z0-9._ -]/', '', (string) ($_GET['app'] ?? '')), 0, 20);
    $every = isset($_GET['every']) ? max(0, min(600, (int) $_GET['every'])) : 0;
    $done = isset($_GET['done']) ? (int) $_GET['done'] : 0;
    $ok = !empty($_GET['ok']);
    $message = substr(trim((string) preg_replace('/\s+/', ' ', (string) ($_GET['message'] ?? ''))), 0, 200);
    $address = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
    $now = time();
    try {
        $state = deviceSyncUpdate(static function (array &$state) use ($serial, $app, $every, $done, $ok, $message, $address, $now): void {
            $device = isset($state['devices'][$serial]) && is_array($state['devices'][$serial])
                ? $state['devices'][$serial] : [];
            $device['seen'] = $now;
            $device['app'] = $app;
            $device['ip'] = $address;
            $device['every'] = $every;
            if ($done > 0) {
                $device['done'] = $done;
                $device['done_at'] = $now;
                $device['ok'] = $ok;
                $device['message'] = $message;
            }
            $state['devices'][$serial] = $device;
            if (count($state['devices']) > 300) {      // keep the most recently seen
                uasort($state['devices'], static function (array $a, array $b): int {
                    return (int) ($b['seen'] ?? 0) <=> (int) ($a['seen'] ?? 0);
                });
                $state['devices'] = array_slice($state['devices'], 0, 300, true);
            }
        });
    } catch (Throwable $error) {
        http_response_code(500);
        echo json_encode(['error' => 'state']);
        exit;
    }
    $request = $state['request'];
    // An older app would treat a kind of request it does not know as "sync
    // items", so it is not given one; the Scanners tab says it needs an update.
    if ($request !== null && !deviceUnderstandsScope(['every' => $every], (string) ($request['scope'] ?? 'items'))) {
        $request = null;
    }
    echo json_encode(['request' => $request === null ? null : [
        'id' => (int) ($request['id'] ?? 0),
        'scope' => (string) ($request['scope'] ?? 'items'),
        'age' => max(0, $now - (int) ($request['at'] ?? 0)),
    ]]);
    exit;
}

/** Records a "sync now" request for the scanners to pick up: one of DEVICE_SYNC_SCOPES. */
function requestDeviceSync(string $scope): void
{
    if (!isset(DEVICE_SYNC_SCOPES[$scope])) {
        throw new RuntimeException('Choose what the scanners should sync.');
    }
    deviceSyncUpdate(static function (array &$state) use ($scope): void {
        // Millisecond time, so every request has a higher id than the one before.
        $id = max((int) floor(microtime(true) * 1000), (int) ($state['request']['id'] ?? 0) + 1);
        $state['request'] = ['id' => $id, 'scope' => $scope, 'at' => time()];
    });
}

function forgetDevice(string $serial): void
{
    deviceSyncUpdate(static function (array &$state) use ($serial): void {
        unset($state['devices'][$serial]);
    });
}

/** The request and each scanner's status, for the Scanners tab. */
function scannerStatus(): array
{
    $state = deviceSyncState();
    $now = time();
    $request = $state['request'];
    // Scanners ignore a request older than a day; they have had a daily sync since.
    $active = $request !== null && $now - (int) ($request['at'] ?? 0) <= 86400;
    $devices = [];
    $online = 0;
    $finished = 0;
    foreach ($state['devices'] as $serial => $device) {
        $seen = (int) ($device['seen'] ?? 0);
        $isOnline = $now - $seen < deviceOnlineSeconds($device);
        $online += $isOnline ? 1 : 0;
        $sync = 'none';
        $message = '';
        if ($request !== null && (int) ($device['done'] ?? 0) === (int) ($request['id'] ?? -1)) {
            $sync = !empty($device['ok']) ? 'done' : 'failed';
            $message = (string) ($device['message'] ?? '');
            $finished += $sync === 'done' ? 1 : 0;
        } elseif ($active && !deviceUnderstandsScope($device, (string) ($request['scope'] ?? 'items'))) {
            $sync = 'unsupported';
        } elseif ($active) {
            $sync = $isOnline ? 'pending' : 'offline';
        }
        $devices[] = [
            'serial' => (string) $serial,
            'app' => (string) ($device['app'] ?? ''),
            'ip' => (string) ($device['ip'] ?? ''),
            'seen' => $seen,
            'online' => $isOnline,
            'sync' => $sync,
            'message' => $message,
            'done_at' => (int) ($device['done_at'] ?? 0),
        ];
    }
    usort($devices, static function (array $a, array $b): int {
        return [$b['online'], $a['serial']] <=> [$a['online'], $b['serial']];
    });
    return ['now' => $now, 'request' => $request, 'active' => $active, 'devices' => $devices,
        'online' => $online, 'finished' => $finished];
}

/** "just now", "40 sec ago", "5 min ago", "3 hr ago", or a date. */
function agoLabel(int $seconds, int $timestamp): string
{
    if ($seconds < 10) {
        return 'just now';
    }
    if ($seconds < 90) {
        return $seconds . ' sec ago';
    }
    if ($seconds < 5400) {
        return (int) round($seconds / 60) . ' min ago';
    }
    if ($seconds < 86400) {
        return (int) round($seconds / 3600) . ' hr ago';
    }
    return date('M j, g:i A', $timestamp);
}

/** The status block of the Scanners tab; also served alone to refresh it in place. */
function renderScannerStatus(array $status): string
{
    $request = $status['request'];
    $devices = $status['devices'];
    ob_start();
    ?>
    <div class="card-head" data-scanner-rows>
        <div>
            <h2>Scanners</h2>
            <p class="subtle"><?php if (!$devices): ?>
                No scanner has checked in yet. Scanners appear here once they run app v6 or newer with this server's address.
            <?php else: ?>
                <?= (int) $status['online'] ?> of <?= count($devices) ?> online<?php if ($request !== null): ?>
                &middot; last request: <strong><?= h(DEVICE_SYNC_SCOPES[(string) ($request['scope'] ?? '')] ?? 'Sync') ?></strong>,
                <?= h(agoLabel($status['now'] - (int) $request['at'], (int) $request['at'])) ?>
                &middot; <?= (int) $status['finished'] ?> of <?= count($devices) ?> done<?php endif; ?>
            <?php endif; ?></p>
        </div>
        <span class="tag neutral">Updates every second</span>
    </div>
    <?php if ($devices): ?>
    <div class="table-wrap">
        <table class="catalog-table">
            <thead>
            <tr>
                <th>Scanner</th>
                <th>App</th>
                <th>Connection</th>
                <th>Last request</th>
                <th><span class="sr-only">Actions</span></th>
            </tr>
            </thead>
            <tbody>
            <?php foreach ($devices as $device): ?>
                <tr>
                    <td><strong><?= h($device['serial']) ?></strong><?php if ($device['ip'] !== ''): ?>
                        <div class="subtle"><?= h($device['ip']) ?></div><?php endif; ?></td>
                    <td><?= h($device['app'] !== '' ? $device['app'] : '-') ?></td>
                    <td>
                        <span class="status-pill <?= $device['online'] ? 'active' : 'ended' ?>"><?= $device['online'] ? 'Online' : 'Offline' ?></span>
                        <div class="subtle">seen <?= h(agoLabel($status['now'] - $device['seen'], $device['seen'])) ?></div>
                    </td>
                    <td>
                        <?php if ($device['sync'] === 'done'): ?>
                            <span class="status-pill active">Done</span>
                            <div class="subtle"><?= h(agoLabel($status['now'] - $device['done_at'], $device['done_at'])) ?><?= $device['message'] !== '' ? ' &middot; ' . h($device['message']) : '' ?></div>
                        <?php elseif ($device['sync'] === 'failed'): ?>
                            <span class="status-pill failed">Failed</span>
                            <div class="subtle"><?= h($device['message'] !== '' ? $device['message'] : 'The scanner could not do it.') ?></div>
                        <?php elseif ($device['sync'] === 'unsupported'): ?>
                            <span class="status-pill ended">Needs app update</span>
                            <div class="subtle">this scanner's app is too old for that request</div>
                        <?php elseif ($device['sync'] === 'pending'): ?>
                            <span class="status-pill scheduled">Waiting</span>
                            <div class="subtle">starts in a few seconds</div>
                        <?php elseif ($device['sync'] === 'offline'): ?>
                            <span class="status-pill ended">Not reached</span>
                            <div class="subtle">will do it when it is back</div>
                        <?php else: ?>
                            <span class="subtle">-</span>
                        <?php endif; ?>
                    </td>
                    <td class="num">
                        <?php if (!$device['online']): ?>
                        <form method="post">
                            <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                            <input type="hidden" name="action" value="forget_scanner">
                            <input type="hidden" name="serial" value="<?= h($device['serial']) ?>">
                            <button class="secondary btn-sm" type="submit" title="Remove this scanner from the list">Remove</button>
                        </form>
                        <?php endif; ?>
                    </td>
                </tr>
            <?php endforeach; ?>
            </tbody>
        </table>
    </div>
    <?php endif;
    return (string) ob_get_clean();
}

function outputAppUpdate(): void
{
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    $path = __DIR__ . '/price-verifier.apk';
    if (!is_file($path)) {
        http_response_code(404);
        echo json_encode(['available' => false], JSON_UNESCAPED_SLASHES);
        exit;
    }
    $info = appUpdateInfo();
    $size = filesize($path) ?: 0;
    $modified = filemtime($path) ?: 0;
    $releaseId = hash('sha256', $info['version'] . '|' . $size . '|' . $modified);
    echo json_encode([
        'available' => true,
        'file' => 'price-verifier.apk',
        'url' => absoluteServerUrl('price-verifier.apk'),
        'size' => $size,
        'modified' => $modified,
        'release_id' => $releaseId,
        'version' => $info['version'],
        'changes' => $info['changes'],
    ], JSON_UNESCAPED_SLASHES);
    exit;
}

if (isset($_GET['app_update'])) {
    outputAppUpdate();
}

function slots(): array
{
    return [
        'catalog' => [
            'label' => 'Product catalog',
            'hint' => 'CSV, TXT or Excel (.xlsx, .xls)',
            'format' => 'barcode,name,uom,category,price,item_code',
            'accept' => '.csv,.txt,.xlsx,.xls,text/csv,text/plain,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel',
            'files' => ['catalog.csv', 'catalog.txt'],
            'max' => 100 * 1024 * 1024,
        ],
        'app' => [
            'label' => 'Android app',
            'hint' => 'APK installer',
            'accept' => '.apk,application/vnd.android.package-archive',
            'files' => ['price-verifier.apk'],
            'max' => 50 * 1024 * 1024,
        ],
        'logo' => [
            'label' => 'Store logo',
            'hint' => 'PNG or JPG, auto-compressed',
            'accept' => '.png,.jpg,.jpeg,image/png,image/jpeg',
            'files' => ['logo.png', 'logo.jpg'],
            'max' => 10 * 1024 * 1024,
        ],
        'banner' => [
            'label' => 'Display banner',
            'hint' => 'PNG or JPG, auto-compressed',
            'accept' => '.png,.jpg,.jpeg,image/png,image/jpeg',
            'files' => ['banner.jpg', 'banner.png'],
            'max' => 20 * 1024 * 1024,
        ],
        'display_media' => [
            'label' => 'Scheduled display media',
            'hint' => 'Separate image and video uploads',
            'format' => '[3CODE_]MMDDYY[_SECONDS].ext',
            'example' => 'Any JPG, PNG, or MP4 can be uploaded: it is named automatically to play for one month from today. To change the end date or the seconds per turn, rename it. 000_010126_20.mp4 stays active through January 1, 2026 and displays for 20 seconds per turn. The 3-character code is optional. A file uploaded with a name already in this format keeps it.',
            'accept' => '.jpg,.jpeg,.png,.mp4,image/jpeg,image/png,video/mp4',
            'files' => [],
            'max' => 100 * 1024 * 1024,
            'multiple' => true,
        ],
        'video' => [
            'label' => 'Idle video',
            'hint' => 'MP4, compressed if ffmpeg is available',
            'accept' => '.mp4,video/mp4',
            'files' => ['video.mp4'],
            'max' => 100 * 1024 * 1024,
        ],
        'marquee' => [
            'label' => 'Scrolling message',
            'hint' => 'Type it, or upload TXT',
            'accept' => '.txt,text/plain',
            'files' => ['marquee.txt'],
            'max' => 256 * 1024,
        ],
    ];
}

function humanBytes(int $bytes): string
{
    if ($bytes >= 1024 * 1024) {
        return number_format($bytes / (1024 * 1024), 1) . ' MB';
    }
    if ($bytes >= 1024) {
        return number_format($bytes / 1024, 1) . ' KB';
    }
    return $bytes . ' B';
}

function currentFiles(array $slot): array
{
    if (!empty($slot['multiple']) && empty($slot['files'])) {
        return scheduleMediaFiles();
    }
    $found = [];
    foreach ($slot['files'] as $name) {
        $path = __DIR__ . '/' . $name;
        if (is_file($path)) {
            $found[] = [
                'name' => $name,
                'size' => filesize($path) ?: 0,
                'time' => filemtime($path) ?: time(),
            ];
        }
    }
    return $found;
}

function uploadError(int $code): string
{
    $messages = [
        UPLOAD_ERR_INI_SIZE => 'The file is larger than the server upload limit.',
        UPLOAD_ERR_FORM_SIZE => 'The file is larger than the form upload limit.',
        UPLOAD_ERR_PARTIAL => 'The upload was interrupted. Please try again.',
        UPLOAD_ERR_NO_FILE => 'Choose a file before uploading.',
        UPLOAD_ERR_NO_TMP_DIR => 'The server upload folder is missing.',
        UPLOAD_ERR_CANT_WRITE => 'The server could not save the upload.',
        UPLOAD_ERR_EXTENSION => 'A server extension stopped the upload.',
    ];
    return isset($messages[$code]) ? $messages[$code] : 'The upload failed.';
}

function textFileIsSafe(string $path): bool
{
    $size = filesize($path) ?: 0;
    $sample = file_get_contents($path, false, null, 0, min(8192, $size));
    return $sample !== false && strpos($sample, "\0") === false;
}

function chooseTarget(string $slotKey, string $tempPath, string $originalName): string
{
    $ext = strtolower(pathinfo($originalName, PATHINFO_EXTENSION));

    if ($slotKey === 'catalog') {
        if ($ext === 'xlsx' || $ext === 'xls') {
            // Converted to catalog.csv on upload (replaceCatalogUpload), so the
            // scanners keep syncing a plain CSV.
            if (excelWorkbookKind($tempPath) === '') {
                throw new RuntimeException('This file is not a real Excel workbook (some systems save text or a web page'
                    . ' with an Excel file name). Open it in Excel, save it as an Excel workbook (.xlsx) or CSV, and upload that.');
            }
            return 'catalog.csv';
        }
        if (!in_array($ext, ['csv', 'txt'], true) || !textFileIsSafe($tempPath)) {
            throw new RuntimeException('Upload a valid CSV, TXT or Excel (.xlsx, .xls) product catalog.');
        }
        return $ext === 'csv' ? 'catalog.csv' : 'catalog.txt';
    }

    if ($slotKey === 'marquee') {
        if ($ext !== 'txt' || !textFileIsSafe($tempPath)) {
            throw new RuntimeException('Upload a valid TXT scrolling message.');
        }
        return 'marquee.txt';
    }

    if ($slotKey === 'app') {
        $header = file_get_contents($tempPath, false, null, 0, 4);
        if ($ext !== 'apk' || $header === false || substr($header, 0, 2) !== 'PK') {
            throw new RuntimeException('Upload a valid Android APK installer.');
        }
        return 'price-verifier.apk';
    }

    if ($slotKey === 'logo' || $slotKey === 'banner') {
        $image = @getimagesize($tempPath);
        $type = is_array($image) && isset($image[2]) ? $image[2] : 0;
        if ($type !== IMAGETYPE_PNG && $type !== IMAGETYPE_JPEG) {
            throw new RuntimeException('Upload a valid PNG or JPG image.');
        }
        return $slotKey . ($type === IMAGETYPE_PNG ? '.png' : '.jpg');
    }

    if ($slotKey === 'video') {
        $header = file_get_contents($tempPath, false, null, 0, 12);
        if ($ext !== 'mp4' || $header === false || strlen($header) < 12 || substr($header, 4, 4) !== 'ftyp') {
            throw new RuntimeException('Upload a valid MP4 video.');
        }
        return 'video.mp4';
    }

    if ($slotKey === 'display_media') {
        $name = basename(str_replace('\\', '/', $originalName));
        if (!isSupportedScheduleMediaName($name) && in_array($ext, ['jpg', 'jpeg', 'png', 'mp4'], true)) {
            // An awkward or reserved file name does not matter: a name that is not
            // in the scheduled format is replaced by an automatic one on upload.
            $name = 'upload.' . $ext;
        }
        if (in_array($ext, ['jpg', 'jpeg', 'png'], true)) {
            $image = @getimagesize($tempPath);
            $type = is_array($image) && isset($image[2]) ? $image[2] : 0;
            if ($type !== IMAGETYPE_PNG && $type !== IMAGETYPE_JPEG) {
                throw new RuntimeException('Upload a valid JPG or PNG scheduled image.');
            }
            return $name;
        }
        if ($ext === 'mp4') {
            $header = file_get_contents($tempPath, false, null, 0, 12);
            if ($header === false || strlen($header) < 12 || substr($header, 4, 4) !== 'ftyp') {
                throw new RuntimeException('Upload a valid MP4 scheduled video.');
            }
            return $name;
        }
        throw new RuntimeException('Upload only JPG, PNG, or MP4 scheduled media.');
    }

    throw new RuntimeException('This upload type is not allowed.');
}

function replaceUpload(string $targetName, string $tempPath, string $slotKey): bool
{
    $staged = __DIR__ . '/.upload-' . bin2hex(random_bytes(8)) . '.tmp';
    if (!move_uploaded_file($tempPath, $staged)) {
        throw new RuntimeException('The server could not move the uploaded file.');
    }
    @chmod($staged, 0644);
    $optimized = optimizeUpload($slotKey, $targetName, $staged);
    $source = (string) $optimized['path'];
    $compressed = !empty($optimized['compressed']);

    $target = __DIR__ . '/' . $targetName;
    if (!@rename($source, $target)) {
        @unlink($target);
        if (!@rename($source, $target)) {
            @unlink($source);
            if ($source !== $staged) {
                @unlink($staged);
            }
            throw new RuntimeException('The server could not replace the existing file.');
        }
    }
    if ($source !== $staged) {
        @unlink($staged);
    }
    return $compressed;
}

function removeAlternateFiles(array $slot, string $keep): void
{
    foreach ($slot['files'] as $name) {
        if ($name !== $keep) {
            @unlink(__DIR__ . '/' . $name);
        }
    }
}

function functionAvailable(string $name): bool
{
    if (!function_exists($name)) {
        return false;
    }
    $disabled = (string) ini_get('disable_functions');
    if ($disabled === '') {
        return true;
    }
    $blocked = array_map('trim', explode(',', strtolower($disabled)));
    return !in_array(strtolower($name), $blocked, true);
}

function optimizeUpload(string $slotKey, string $targetName, string $path): array
{
    $ext = strtolower(pathinfo($targetName, PATHINFO_EXTENSION));
    if (in_array($ext, ['jpg', 'jpeg', 'png'], true)) {
        $optimized = optimizeImageUpload($slotKey, $targetName, $path);
        if ($optimized !== null) {
            return ['path' => $optimized, 'compressed' => true];
        }
    }
    if ($ext === 'mp4') {
        $optimized = optimizeVideoUpload($path);
        if ($optimized !== null) {
            return ['path' => $optimized, 'compressed' => true];
        }
    }
    return ['path' => $path, 'compressed' => false];
}

function optimizeImageUpload(string $slotKey, string $targetName, string $source): ?string
{
    if (!extension_loaded('gd')) {
        return null;
    }
    $info = @getimagesize($source);
    if (!is_array($info) || empty($info[0]) || empty($info[1]) || empty($info[2])) {
        return null;
    }
    $width = (int) $info[0];
    $height = (int) $info[1];
    $type = (int) $info[2];
    if ($type === IMAGETYPE_JPEG) {
        $image = @imagecreatefromjpeg($source);
    } elseif ($type === IMAGETYPE_PNG) {
        $image = @imagecreatefrompng($source);
    } else {
        return null;
    }
    if (!$image) {
        return null;
    }

    $maxWidth = $slotKey === 'logo' ? 1200 : 1920;
    $maxHeight = $slotKey === 'logo' ? 1200 : 1080;
    $scale = min(1.0, $maxWidth / max(1, $width), $maxHeight / max(1, $height));
    $newWidth = max(1, (int) floor($width * $scale));
    $newHeight = max(1, (int) floor($height * $scale));
    $canvas = imagecreatetruecolor($newWidth, $newHeight);
    if (!$canvas) {
        imagedestroy($image);
        return null;
    }
    if ($type === IMAGETYPE_PNG) {
        imagealphablending($canvas, false);
        imagesavealpha($canvas, true);
        imagefill($canvas, 0, 0, imagecolorallocatealpha($canvas, 0, 0, 0, 127));
    }
    imagecopyresampled($canvas, $image, 0, 0, 0, 0, $newWidth, $newHeight, $width, $height);

    $ext = strtolower(pathinfo($targetName, PATHINFO_EXTENSION));
    $out = __DIR__ . '/.upload-image-' . bin2hex(random_bytes(8)) . '.' . ($ext === 'png' ? 'png' : 'jpg');
    $ok = $ext === 'png' ? imagepng($canvas, $out, 7) : imagejpeg($canvas, $out, 82);
    imagedestroy($canvas);
    imagedestroy($image);

    if (!$ok || !is_file($out) || filesize($out) <= 0 || filesize($out) >= filesize($source)) {
        @unlink($out);
        return null;
    }
    @chmod($out, 0644);
    return $out;
}

function optimizeVideoUpload(string $source): ?string
{
    if (!functionAvailable('exec')) {
        return null;
    }
    $probe = [];
    $probeCode = 1;
    @exec('command -v ffmpeg 2>/dev/null', $probe, $probeCode);
    $ffmpeg = $probeCode === 0 && !empty($probe[0]) ? trim((string) $probe[0]) : 'ffmpeg';
    $out = __DIR__ . '/.upload-video-' . bin2hex(random_bytes(8)) . '.mp4';
    $cmd = escapeshellcmd($ffmpeg)
        . ' -y -i ' . escapeshellarg($source)
        . ' -vf ' . escapeshellarg("scale='min(1280,iw)':-2")
        . ' -c:v libx264 -preset veryfast -crf 28 -c:a aac -b:a 96k -movflags +faststart '
        . escapeshellarg($out) . ' 2>&1';
    $output = [];
    $code = 1;
    @exec($cmd, $output, $code);
    if ($code !== 0 || !is_file($out) || filesize($out) <= 0 || filesize($out) >= filesize($source)) {
        @unlink($out);
        return null;
    }
    @chmod($out, 0644);
    return $out;
}

function cleanCatalogValue(string $value): string
{
    return trim(str_replace(["\r", "\n"], ' ', $value));
}

function normalizeCatalogHeader(string $value): string
{
    $cleaned = strtolower(trim($value, "\xEF\xBB\xBF \t\n\r\0\x0B"));
    return str_replace(['-', '_'], ' ', $cleaned);
}

function catalogColumn(array $headers, array $aliases): int
{
    foreach ($aliases as $alias) {
        $index = array_search(normalizeCatalogHeader($alias), $headers, true);
        if ($index !== false) {
            return (int) $index;
        }
    }
    return -1;
}

function catalogCell(array $row, int $index): string
{
    return $index >= 0 && isset($row[$index]) ? cleanCatalogValue((string) $row[$index]) : '';
}

/** Display-only peso formatting; the stored catalog value is left untouched. */
function catalogPriceLabel(string $price): string
{
    $clean = str_replace([',', "\u{20B1}", ' '], '', $price);
    return is_numeric($clean) ? "\u{20B1}" . number_format((float) $clean, 2) : $price;
}

function activeCatalogPath(): string
{
    if (is_file(__DIR__ . '/catalog.csv')) {
        return __DIR__ . '/catalog.csv';
    }
    if (is_file(__DIR__ . '/catalog.txt')) {
        return __DIR__ . '/catalog.txt';
    }
    return __DIR__ . '/catalog.csv';
}

function catalogSchema($handle): array
{
    $headerRow = fgetcsv($handle);
    if ($headerRow === false) {
        return [];
    }
    $schema = catalogSchemaFromHeaders($headerRow);
    if ($schema === null) {
        throw new RuntimeException('The existing catalog must include barcode, name, and price columns.');
    }
    return $schema;
}

/** Header names the scanner app itself understands for each catalog field. */
function catalogDefaultAliases(): array
{
    return [
        'barcode' => ['barcode', 'bar code', 'upc', 'ean', 'code'],
        'name' => ['name', 'product', 'description', 'item', 'item name'],
        'uom' => ['uom', 'unit', 'unit of measure'],
        'category' => ['category', 'department'],
        'price' => ['price', 'amount', 'retail', 'srp', 'selling price'],
        'item_code' => ['item code', 'item_code', 'itemcode', 'sku', 'item sku'],
    ];
}

function catalogFieldLabels(): array
{
    return ['barcode' => 'Barcode', 'name' => 'Name', 'price' => 'Price', 'uom' => 'Unit',
        'category' => 'Category', 'item_code' => 'Item code'];
}

/** Extra header names added in the portal (Catalog > Column names), per field. */
function catalogCustomAliases(): array
{
    static $custom = null;
    if ($custom === null) {
        $custom = array_fill_keys(array_keys(catalogDefaultAliases()), []);
        $saved = is_file(CATALOG_COLUMNS_FILE)
            ? json_decode((string) file_get_contents(CATALOG_COLUMNS_FILE), true) : null;
        if (is_array($saved)) {
            foreach ($custom as $field => $unused) {
                foreach ((array) ($saved[$field] ?? []) as $name) {
                    if (is_string($name) && trim($name) !== '') {
                        $custom[$field][] = trim($name);
                    }
                }
            }
        }
    }
    return $custom;
}

/** Accepted header names for each catalog field (CSV, TXT and Excel uploads): built-in plus custom. */
function catalogHeaderAliases(): array
{
    $aliases = catalogDefaultAliases();
    foreach (catalogCustomAliases() as $field => $names) {
        $aliases[$field] = array_merge($aliases[$field], $names);
    }
    return $aliases;
}

/**
 * Saves the extra header names typed in the portal: comma-separated names per
 * field. A name may belong to one field only, built-in names included.
 */
function saveCatalogColumns(array $input): void
{
    $taken = [];
    foreach (catalogDefaultAliases() as $field => $names) {
        foreach ($names as $name) {
            $taken[normalizeCatalogHeader($name)] = $field;
        }
    }
    $labels = catalogFieldLabels();
    $custom = [];
    foreach ($labels as $field => $label) {
        $custom[$field] = [];
        foreach (explode(',', (string) ($input[$field] ?? '')) as $name) {
            $name = trim(preg_replace('/\s+/', ' ', $name));
            $key = normalizeCatalogHeader($name);
            if ($key === '') {
                continue;
            }
            if (strlen($name) > 60 || preg_match('/[\x00-\x1F"]/', $name)) {
                throw new RuntimeException('Column names must be at most 60 characters, without quotes.');
            }
            if (isset($taken[$key])) {
                if ($taken[$key] === $field) {
                    continue;   // already accepted for this field
                }
                throw new RuntimeException('"' . $name . '" is already used for ' . $labels[$taken[$key]]
                    . ', so it cannot also mean ' . $label . '.');
            }
            $taken[$key] = $field;
            $custom[$field][] = $name;
        }
        if (count($custom[$field]) > 20) {
            throw new RuntimeException('Add at most 20 extra names per column.');
        }
    }
    $json = json_encode($custom, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if ($json === false || file_put_contents(CATALOG_COLUMNS_FILE, $json . "\n", LOCK_EX) === false) {
        throw new RuntimeException('The column names could not be saved.');
    }
    @chmod(CATALOG_COLUMNS_FILE, 0640);
}

/** True when a header row relies on a custom name the scanner app would not recognise. */
function catalogUsesCustomHeaders(array $headerRow): bool
{
    $headers = array_map(static function ($value): string {
        return normalizeCatalogHeader((string) $value);
    }, $headerRow);
    foreach (catalogDefaultAliases() as $field => $names) {
        $withCustom = catalogColumn($headers, catalogHeaderAliases()[$field]);
        if ($withCustom >= 0 && $withCustom !== catalogColumn($headers, $names)) {
            return true;
        }
    }
    return false;
}

/**
 * Rewrites a CSV/TXT catalog whose headers use custom names with the standard
 * header row, so the scanner app can read it. Returns the staged path.
 */
function csvToStandardCatalog(string $source): string
{
    $in = fopen($source, 'rb');
    $staged = __DIR__ . '/.catalog-' . bin2hex(random_bytes(8)) . '.tmp';
    $out = $in === false ? false : fopen($staged, 'wb');
    if ($in === false || $out === false) {
        if ($in !== false) {
            fclose($in);
        }
        throw new RuntimeException('The server could not rewrite the uploaded catalog.');
    }
    try {
        $schema = catalogSchemaFromHeaders((array) fgetcsv($in));
        if ($schema === null) {
            throw new RuntimeException('The uploaded catalog must include barcode, name and price columns.');
        }
        fputcsv($out, ['barcode', 'name', 'uom', 'category', 'price', 'item_code'], ',', '"', '');
        while (($row = fgetcsv($in)) !== false) {
            if ($row === [null]) {
                continue;
            }
            fputcsv($out, [
                preg_replace('/\s+/', '', catalogCell($row, $schema['barcode'])),
                catalogCell($row, $schema['name']),
                catalogCell($row, $schema['uom']),
                catalogCell($row, $schema['category']),
                catalogCell($row, $schema['price']),
                catalogCell($row, $schema['item_code']),
            ], ',', '"', '');
        }
    } catch (Throwable $error) {
        fclose($in);
        fclose($out);
        @unlink($staged);
        throw $error;
    }
    fclose($in);
    fclose($out);
    return $staged;
}

/** Column positions for a header row, or null when barcode, name or price is missing. */
function catalogSchemaFromHeaders(array $headerRow): ?array
{
    $headers = array_map(static function ($value): string {
        return normalizeCatalogHeader((string) $value);
    }, $headerRow);
    $schema = [];
    foreach (catalogHeaderAliases() as $field => $aliases) {
        $schema[$field] = catalogColumn($headers, $aliases);
    }
    if ($schema['barcode'] < 0 || $schema['name'] < 0 || $schema['price'] < 0) {
        return null;
    }
    return $schema;
}

/**
 * Stages an uploaded catalog, converting it to CSV when it is an Excel
 * workbook, checks it, and only then changes the live catalog: the upload
 * either replaces it or, with $merge, is added to it (matched by barcode).
 * Returns the row counts and the catalog file name for the upload message.
 */
function replaceCatalogUpload(string $tempPath, string $originalName, string $targetName, bool $merge = false): array
{
    $staged = __DIR__ . '/.upload-' . bin2hex(random_bytes(8)) . '.tmp';
    if (!move_uploaded_file($tempPath, $staged)) {
        throw new RuntimeException('The server could not move the uploaded file.');
    }
    $temporary = [$staged];
    try {
        $excel = in_array(strtolower(pathinfo($originalName, PATHINFO_EXTENSION)), ['xlsx', 'xls'], true);
        $numericBarcodes = 0;
        if ($excel) {
            // By content, not by name: an .xls is sometimes a renamed .xlsx and the reverse.
            $converted = excelWorkbookKind($staged) === 'xls' ? xlsToCatalogCsv($staged) : xlsxToCatalogCsv($staged);
            $source = $converted['path'];
            $numericBarcodes = $converted['numericBarcodes'];
            $temporary[] = $source;
        } else {
            $source = $staged;
            $header = fopen($staged, 'rb');
            $headerRow = $header === false ? false : fgetcsv($header);
            if ($header !== false) {
                fclose($header);
            }
            if (is_array($headerRow) && catalogUsesCustomHeaders($headerRow)) {
                $source = csvToStandardCatalog($staged);
                $temporary[] = $source;
            }
        }
        $stats = catalogUploadStats($source);
        $stats['excel'] = $excel;
        $stats['numericBarcodes'] = $numericBarcodes;
        if ($stats['valid'] === 0) {
            throw new RuntimeException('No products with a barcode, name and valid price were found in '
                . $originalName . '. The current catalog was kept.');
        }
        $current = activeCatalogPath();
        $stats['previous'] = is_file($current) ? catalogItemCount($current) : null;
        $stats['merged'] = null;
        if ($merge && is_file($current)) {
            // The merged catalog is always written as catalog.csv with the standard columns.
            $stats['merged'] = mergeCatalogUpload($source, $current);
            $source = $stats['merged']['path'];
            $temporary[] = $source;
            $targetName = 'catalog.csv';
            // What scanners will import from the combined catalog.
            $stats['merged']['ready'] = catalogUploadStats($source)['valid'];
        }
        $stats['target'] = $targetName;
        @chmod($source, 0644);
        $target = __DIR__ . '/' . $targetName;
        if (!@rename($source, $target)) {
            @unlink($target);
            if (!@rename($source, $target)) {
                throw new RuntimeException('The server could not replace the existing file.');
            }
        }
        return $stats;
    } finally {
        foreach (array_unique($temporary) as $file) {
            if (is_file($file)) {
                @unlink($file);
            }
        }
    }
}

/** How many products (rows with a barcode and a name) a catalog file holds, or null when it cannot be read. */
function catalogItemCount(string $path): ?int
{
    $handle = @fopen($path, 'rb');
    if ($handle === false) {
        return null;
    }
    try {
        $schema = catalogSchema($handle);
        $count = 0;
        while ($schema && ($row = fgetcsv($handle)) !== false) {
            if (catalogItemFromRow($row, $schema) !== null) {
                $count++;
            }
        }
        return $count;
    } catch (Throwable $error) {
        return null;
    } finally {
        fclose($handle);
    }
}

/**
 * "Add and update only": writes a staged catalog holding everything in the
 * current catalog plus the uploaded products. A barcode that is already in
 * the catalog is updated in place; a new barcode is added at the end. A blank
 * unit, category or item code in the upload keeps the existing value, so a
 * file with only barcode, name and price does not wipe them.
 */
function mergeCatalogUpload(string $upload, string $current): array
{
    $incoming = [];
    $in = fopen($upload, 'rb');
    if ($in === false) {
        throw new RuntimeException('The uploaded catalog could not be opened.');
    }
    try {
        $headerRow = fgetcsv($in);
        $schema = is_array($headerRow) ? catalogSchemaFromHeaders($headerRow) : null;
        while ($schema !== null && ($row = fgetcsv($in)) !== false) {
            $item = catalogItemFromRow($row, $schema);
            // Same rule as the "items ready to sync" count; a later row for a barcode wins.
            if ($item !== null && !isScientificBarcode($item['barcode']) && catalogPriceIsValid($item['price'])) {
                $incoming[$item['barcode']] = $item;
            }
        }
    } finally {
        fclose($in);
    }

    $staged = __DIR__ . '/.catalog-' . bin2hex(random_bytes(8)) . '.tmp';
    $out = fopen($staged, 'wb');
    if ($out === false) {
        throw new RuntimeException('The catalog update could not be staged.');
    }
    $counts = ['added' => 0, 'updated' => 0, 'unchanged' => 0, 'total' => 0];
    try {
        fputcsv($out, ['barcode', 'name', 'uom', 'category', 'price', 'item_code']);
        $old = fopen($current, 'rb');
        if ($old === false) {
            throw new RuntimeException('The current catalog could not be opened.');
        }
        try {
            try {
                $oldSchema = catalogSchema($old);
            } catch (Throwable $error) {
                throw new RuntimeException('The current catalog cannot be read, so items cannot be added to it.'
                    . ' Choose "Replace the whole catalog" instead.');
            }
            while ($oldSchema && ($row = fgetcsv($old)) !== false) {
                $existing = catalogItemFromRow($row, $oldSchema);
                if ($existing === null) {
                    continue;
                }
                $barcode = $existing['barcode'];
                if (array_key_exists($barcode, $incoming)) {
                    if ($incoming[$barcode] === null) {
                        continue;    // a repeat of a barcode that was already written
                    }
                    $updated = $incoming[$barcode];
                    foreach (['uom', 'category', 'item_code'] as $field) {
                        if ($updated[$field] === '') {
                            $updated[$field] = $existing[$field];
                        }
                    }
                    // Loose comparison: "17.5" and "17.50" are the same price.
                    if ($updated == $existing) {
                        $counts['unchanged']++;
                        writeCatalogItemLine($out, $existing);
                    } else {
                        $counts['updated']++;
                        writeCatalogItemLine($out, $updated);
                    }
                    $incoming[$barcode] = null;
                } else {
                    writeCatalogItemLine($out, $existing);
                }
                $counts['total']++;
            }
        } finally {
            fclose($old);
        }
        foreach ($incoming as $item) {
            if ($item !== null) {
                writeCatalogItemLine($out, $item);
                $counts['added']++;
                $counts['total']++;
            }
        }
    } catch (Throwable $error) {
        fclose($out);
        @unlink($staged);
        throw $error;
    }
    fclose($out);
    return $counts + ['path' => $staged];
}

/**
 * Excel turns long barcodes into scientific notation (4.80653E+11) when a CSV
 * is saved from it. The original digits are lost, so such rows cannot be matched.
 */
function isScientificBarcode(string $barcode): bool
{
    return preg_match('/^\d+(\.\d+)?e[+-]?\d+$/i', $barcode) === 1;
}

/** Mirrors the app's price parser: a number with an optional currency prefix. */
function catalogPriceIsValid(string $price): bool
{
    $cleaned = preg_replace('/^(?:php|p|\$|\x{20B1})\s*/iu', '', str_replace(',', '', trim($price)));
    $cleaned = preg_replace('/\s+/', '', (string) $cleaned);
    return preg_match('/^(\d+(\.\d*)?|\.\d+)$/', (string) $cleaned) === 1;
}

/** Checks a staged catalog and counts the rows scanners will import. */
function catalogUploadStats(string $path): array
{
    $handle = fopen($path, 'rb');
    if ($handle === false) {
        throw new RuntimeException('The uploaded catalog could not be opened.');
    }
    try {
        $headerRow = fgetcsv($handle);
        if ($headerRow === false) {
            throw new RuntimeException('The uploaded catalog is empty.');
        }
        $schema = catalogSchemaFromHeaders($headerRow);
        if ($schema === null) {
            throw new RuntimeException('The uploaded catalog must include barcode, name and price columns.');
        }
        $valid = 0;
        $scientific = 0;
        $invalid = 0;
        while (($row = fgetcsv($handle)) !== false) {
            if ($row === [null]) {
                continue;
            }
            $barcode = preg_replace('/\s+/', '', catalogCell($row, $schema['barcode']));
            if (isScientificBarcode($barcode)) {
                $scientific++;
            } elseif (($item = catalogItemFromRow($row, $schema)) !== null && catalogPriceIsValid($item['price'])) {
                $valid++;
            } else {
                $invalid++;
            }
        }
        return ['valid' => $valid, 'scientific' => $scientific, 'invalid' => $invalid];
    } finally {
        fclose($handle);
    }
}

/** 'xlsx' (zip-based workbook), 'xls' (Excel 97-2003 compound file) or '' by file signature. */
function excelWorkbookKind(string $path): string
{
    $header = (string) file_get_contents($path, false, null, 0, 8);
    if (substr($header, 0, 4) === "PK\x03\x04") {
        return 'xlsx';
    }
    return $header === "\xD0\xCF\x11\xE0\xA1\xB1\x1A\xE1" ? 'xls' : '';
}

/** Converts the first worksheet of an .xlsx workbook; see spreadsheetToCatalogCsv(). */
function xlsxToCatalogCsv(string $workbook): array
{
    return spreadsheetToCatalogCsv(xlsxRowReader($workbook));
}

/**
 * A reader for the first worksheet of an .xlsx workbook: called with a
 * callback, it calls that with each row's cells and the set of number cells.
 */
function xlsxRowReader(string $workbook): callable
{
    if (!class_exists('ZipArchive') || !class_exists('XMLReader')) {
        throw new RuntimeException('This server cannot read .xlsx files (the PHP zip or xmlreader extension is missing).'
            . ' Save the workbook as CSV and upload that instead.');
    }
    $zip = new ZipArchive();
    if ($zip->open($workbook, ZipArchive::RDONLY) !== true) {
        throw new RuntimeException('The Excel file could not be opened. Make sure it is an .xlsx workbook.');
    }
    try {
        $sheet = xlsxFirstSheetPath($zip);
        $hasStrings = $zip->locateName('xl/sharedStrings.xml') !== false;
        $styles = $zip->getFromName('xl/styles.xml');
    } finally {
        $zip->close();
    }
    $strings = $hasStrings ? xlsxSharedStrings($workbook) : [];
    $zeroPad = $styles === false ? [] : xlsxZeroPadStyles($styles);
    return static function (callable $onRow) use ($workbook, $sheet, $strings, $zeroPad): void {
        xlsxEachRow($workbook, $sheet, $strings, $onRow, $zeroPad);
    };
}

/**
 * Cell style indexes (the "s" attribute of a cell) whose number format is
 * zero-padded, mapped to the digit count, from the workbook's styles.xml.
 */
function xlsxZeroPadStyles(string $stylesXml): array
{
    $formats = [];
    if (preg_match_all('/<(?:\w+:)?numFmt\b[^>]*>/', $stylesXml, $tags)) {
        foreach ($tags[0] as $tag) {
            if (preg_match('/\bnumFmtId="(\d+)"/', $tag, $id) && preg_match('/\bformatCode="([^"]*)"/', $tag, $code)) {
                $formats[(int) $id[1]] = html_entity_decode($code[1], ENT_QUOTES | ENT_XML1, 'UTF-8');
            }
        }
    }
    $zeroPad = [];
    if (preg_match('/<(?:\w+:)?cellXfs\b.*?<\/(?:\w+:)?cellXfs>/s', $stylesXml, $section)
        && preg_match_all('/<(?:\w+:)?xf\b[^>]*>/', $section[0], $styles)) {
        foreach ($styles[0] as $index => $tag) {
            if (preg_match('/\bnumFmtId="(\d+)"/', $tag, $id)) {
                $width = spreadsheetZeroWidth($formats[(int) $id[1]] ?? '');
                if ($width > 0) {
                    $zeroPad[$index] = $width;
                }
            }
        }
    }
    return $zeroPad;
}

/** Converts the first worksheet of an Excel 97-2003 .xls workbook; see spreadsheetToCatalogCsv(). */
function xlsToCatalogCsv(string $workbook): array
{
    return spreadsheetToCatalogCsv(xlsRowReader($workbook));
}

/** A reader for the first worksheet of an .xls workbook; see xlsxRowReader(). */
function xlsRowReader(string $workbook): callable
{
    $stream = xlsWorkbookStream($workbook);
    return static function (callable $onRow) use ($stream): void {
        xlsEachRow($stream, $onRow);
    };
}

/**
 * Writes worksheet rows as a staged UTF-8 catalog CSV with the standard
 * columns. $eachRow is given a callback to call with each row's cells and the
 * set of column indexes stored as numbers. The header row may be preceded by a
 * few title rows; rows before it are ignored. Returns the staged path and how
 * many barcodes were stored as numbers (which loses leading zeros in Excel).
 */
function spreadsheetToCatalogCsv(callable $eachRow): array
{
    $staged = __DIR__ . '/.catalog-' . bin2hex(random_bytes(8)) . '.tmp';
    $out = fopen($staged, 'wb');
    if ($out === false) {
        throw new RuntimeException('The server could not write the converted catalog.');
    }
    $schema = null;
    $scanned = 0;
    $numericBarcodes = 0;
    try {
        fputcsv($out, ['barcode', 'name', 'uom', 'category', 'price', 'item_code'], ',', '"', '');
        $eachRow(static function (array $row, array $numeric) use ($out, &$schema, &$scanned, &$numericBarcodes): void {
            if ($schema === null) {
                if (++$scanned <= 10) {
                    $schema = catalogSchemaFromHeaders($row);
                }
                return;
            }
            $barcode = preg_replace('/\s+/', '', catalogCell($row, $schema['barcode']));
            if ($barcode !== '' && isset($numeric[$schema['barcode']])) {
                $numericBarcodes++;
            }
            // A barcode typed into a number cell can come back as 4.8065330605900E+12.
            if (isScientificBarcode($barcode) && (float) $barcode < 1e15 && floor((float) $barcode) == (float) $barcode) {
                $barcode = sprintf('%.0F', (float) $barcode);
            }
            $fields = [
                $barcode,
                catalogCell($row, $schema['name']),
                catalogCell($row, $schema['uom']),
                catalogCell($row, $schema['category']),
                catalogPriceFromCell(catalogCell($row, $schema['price'])),
                catalogCell($row, $schema['item_code']),
            ];
            if (implode('', $fields) !== '') {
                fputcsv($out, $fields, ',', '"', '');
            }
        });
    } catch (Throwable $error) {
        fclose($out);
        @unlink($staged);
        throw $error;
    }
    fclose($out);
    if ($schema === null) {
        @unlink($staged);
        throw new RuntimeException('The Excel file needs a header row with barcode, name and price columns'
            . ' in its first 10 rows.');
    }
    return ['path' => $staged, 'numericBarcodes' => $numericBarcodes];
}

/**
 * The digit count of a zero-padded number format such as "0000000000000" (how
 * Excel shows barcodes that are kept as numbers), or 0 for any other format.
 */
function spreadsheetZeroWidth(string $format): int
{
    return preg_match('/^(0{2,})(;.*)?$/', trim($format), $match) ? strlen($match[1]) : 0;
}

/** Left-pads a whole number to $width digits, the way Excel displays it in a zero-padded format. */
function spreadsheetZeroPad(string $text, int $width): string
{
    return ctype_digit($text) && strlen($text) < $width ? str_pad($text, $width, '0', STR_PAD_LEFT) : $text;
}

/**
 * Reads the workbook stream out of an Excel 97-2003 file. An .xls is an OLE
 * compound file: a small FAT file system whose "Workbook" stream holds the
 * sheet records. Only that stream is loaded into memory.
 */
function xlsWorkbookStream(string $path): string
{
    $damaged = 'The Excel file is damaged or incomplete.';
    $file = fopen($path, 'rb');
    if ($file === false) {
        throw new RuntimeException('The Excel file could not be opened.');
    }
    try {
        $header = (string) fread($file, 512);
        if (strlen($header) < 512) {
            throw new RuntimeException($damaged);
        }
        $shift = unpack('vsector/vmini', $header, 30);
        $sectorSize = 1 << $shift['sector'];
        if (($sectorSize !== 512 && $sectorSize !== 4096) || $shift['mini'] !== 6) {
            throw new RuntimeException($damaged);
        }
        $info = unpack('VdirStart/Vtransaction/VminiCutoff/VminiFatStart/VminiFatSectors/VdifatStart', $header, 48);
        $readSector = static function (int $sector) use ($file, $sectorSize, $damaged): string {
            if (fseek($file, ($sector + 1) * $sectorSize) !== 0) {
                throw new RuntimeException($damaged);
            }
            $data = fread($file, $sectorSize);
            if ($data === false || $data === '') {
                throw new RuntimeException($damaged);
            }
            return str_pad($data, $sectorSize, "\0");
        };

        // The FAT's own sectors are listed in the header and, for big files, in DIFAT sectors.
        $fatSectors = array_values(unpack('V109', $header, 76));
        $next = $info['difatStart'];
        for ($guard = 0; $next < 0xFFFFFFFA && $guard < 100000; $guard++) {
            $entries = array_values(unpack('V*', $readSector($next)));
            $next = array_pop($entries);
            $fatSectors = array_merge($fatSectors, $entries);
        }
        $fat = '';
        foreach ($fatSectors as $sector) {
            if ($sector < 0xFFFFFFFA) {
                $fat .= $readSector($sector);
            }
        }
        $fatCount = intdiv(strlen($fat), 4);
        $readChain = static function (int $start) use ($fat, $fatCount, $readSector): string {
            $data = '';
            for ($sector = $start, $steps = 0; $sector < $fatCount && $steps <= $fatCount; $steps++) {
                $data .= $readSector($sector);
                $sector = unpack('V', $fat, $sector * 4)[1];
            }
            return $data;
        };

        $directory = $readChain($info['dirStart']);
        $root = null;
        $streams = [];
        for ($offset = 0; $offset + 128 <= strlen($directory); $offset += 128) {
            $nameBytes = max(0, unpack('v', $directory, $offset + 64)[1] - 2);
            $name = strtolower(str_replace("\0", '', substr($directory, $offset, min(64, $nameBytes))));
            $entry = ['start' => unpack('V', $directory, $offset + 116)[1], 'size' => unpack('V', $directory, $offset + 120)[1]];
            $type = ord($directory[$offset + 66]);
            if ($type === 5) {
                $root = $entry;
            } elseif ($type === 2) {
                $streams[$name] = $entry;
            }
        }
        $workbook = $streams['workbook'] ?? $streams['book'] ?? null;
        if ($workbook === null) {
            if (isset($streams['encryptedpackage'])) {
                throw new RuntimeException('This workbook is password-protected. Remove the password in Excel and upload it again.');
            }
            throw new RuntimeException('This .xls file does not contain an Excel workbook. Open it in Excel and save it as .xlsx or CSV.');
        }
        if ($workbook['size'] >= $info['miniCutoff']) {
            return substr($readChain($workbook['start']), 0, $workbook['size']);
        }
        // Streams under 4 KB live in 64-byte sectors inside the root entry's "mini stream".
        if ($root === null) {
            throw new RuntimeException($damaged);
        }
        $miniStream = $readChain($root['start']);
        $miniFat = $readChain($info['miniFatStart']);
        $miniCount = intdiv(strlen($miniFat), 4);
        $data = '';
        for ($sector = $workbook['start'], $steps = 0; $sector < $miniCount && $steps <= $miniCount; $steps++) {
            $data .= substr($miniStream, $sector * 64, 64);
            $sector = unpack('V', $miniFat, $sector * 4)[1];
        }
        return substr($data, 0, $workbook['size']);
    } finally {
        fclose($file);
    }
}

/**
 * Walks the BIFF records of an Excel 97-2003 workbook stream and calls $onRow
 * with each non-empty row of the first visible worksheet (cells 0-based, gaps
 * filled with '') and the set of column indexes stored as numbers.
 */
function xlsEachRow(string $data, callable $onRow): void
{
    $tooOld = 'This .xls file uses a very old Excel format. Open it in Excel and save it as .xlsx or CSV.';
    $length = strlen($data);
    if ($length < 8 || unpack('v', $data, 0)[1] !== 0x0809) {
        throw new RuntimeException($tooOld);
    }
    $version = unpack('v', $data, 4)[1];
    if ($version !== 0x0600 && $version !== 0x0500) {
        throw new RuntimeException($tooOld);
    }
    $biff8 = $version === 0x0600;

    // Workbook globals: where the sheets start, the shared strings, the text code
    // page, and the number formats (FORMAT) that each cell style (XF) points to.
    $codepage = 'CP1252';
    $firstSheet = -1;
    $firstVisible = -1;
    $sstChunks = [];
    $inSst = false;
    $formats = [];
    $styleFormats = [];
    for ($pos = 0; $pos + 4 <= $length;) {
        $type = ord($data[$pos]) | (ord($data[$pos + 1]) << 8);
        $size = ord($data[$pos + 2]) | (ord($data[$pos + 3]) << 8);
        $body = $pos + 4;
        if ($type === 0x000A || $body + $size > $length) {
            break;
        }
        if ($type === 0x002F) {
            throw new RuntimeException('This workbook is password-protected. Remove the password in Excel and upload it again.');
        }
        if ($type === 0x00FC) {
            $sstChunks = [substr($data, $body, $size)];
            $inSst = true;
        } elseif ($type === 0x003C && $inSst) {
            $sstChunks[] = substr($data, $body, $size);
        } else {
            $inSst = false;
            if ($type === 0x0042 && $size >= 2) {
                $page = unpack('v', $data, $body)[1];
                // 1200 is UTF-16 (BIFF8 strings carry their own encoding); 0x8001 is Windows Latin 1.
                $codepage = ($page === 1200 || $page === 0x8001) ? 'CP1252' : 'CP' . $page;
            } elseif ($type === 0x041E && $size >= 3) {
                $formats[unpack('v', $data, $body)[1]] = $biff8
                    ? xlsString($data, $body + 2, true, $codepage)
                    : xlsDecode(substr($data, $body + 3, ord($data[$body + 2])), $codepage);
            } elseif ($type === 0x00E0 && $size >= 4) {
                $styleFormats[] = unpack('v', $data, $body + 2)[1];
            } elseif ($type === 0x0085 && $size >= 6 && ord($data[$body + 5]) === 0) {
                $sheetStart = unpack('V', $data, $body)[1];
                if ($firstSheet < 0) {
                    $firstSheet = $sheetStart;
                }
                if ($firstVisible < 0 && (ord($data[$body + 4]) & 3) === 0) {
                    $firstVisible = $sheetStart;
                }
            }
        }
        $pos = $body + $size;
    }
    $pos = $firstVisible >= 0 ? $firstVisible : $firstSheet;
    if ($pos < 0 || $pos + 4 > $length) {
        throw new RuntimeException('The Excel file contains no worksheet.');
    }
    $strings = $sstChunks ? xlsSharedStrings($sstChunks) : [];
    // Cell styles whose number format is zero-padded, e.g. barcodes shown as 0000000039826.
    $zeroPad = [];
    foreach ($styleFormats as $style => $format) {
        $width = spreadsheetZeroWidth($formats[$format] ?? '');
        if ($width > 0) {
            $zeroPad[$style] = $width;
        }
    }

    $row = -1;
    $cells = [];
    $numeric = [];
    $pendingColumn = -1;
    $depth = 0;
    // A number cell: shown as Excel shows it when its format is zero-padded,
    // otherwise flagged as "stored as a number".
    $setNumber = static function (int $column, float $value, int $style) use (&$cells, &$numeric, $zeroPad): void {
        $text = xlsNumberText($value);
        if (isset($zeroPad[$style])) {
            $cells[$column] = spreadsheetZeroPad($text, $zeroPad[$style]);
        } else {
            $cells[$column] = $text;
            $numeric[$column] = true;
        }
    };
    $flush = static function () use (&$cells, &$numeric, $onRow): void {
        if ($cells) {
            $values = [];
            for ($i = 0, $last = max(array_keys($cells)); $i <= $last; $i++) {
                $values[] = $cells[$i] ?? '';
            }
            $onRow($values, $numeric);
        }
        $cells = [];
        $numeric = [];
    };
    while ($pos + 4 <= $length) {
        $type = ord($data[$pos]) | (ord($data[$pos + 1]) << 8);
        $size = ord($data[$pos + 2]) | (ord($data[$pos + 3]) << 8);
        $body = $pos + 4;
        $pos = $body + $size;
        if ($pos > $length) {
            break;
        }
        if ($type === 0x0809) {
            $depth++;            // the sheet's own BOF, or an embedded chart's
            continue;
        }
        if ($type === 0x000A) {
            if (--$depth <= 0) {
                break;
            }
            continue;
        }
        if ($depth !== 1) {
            continue;
        }
        if ($type === 0x0207) {  // STRING: the text result of the formula cell just before it
            if ($pendingColumn >= 0) {
                $cells[$pendingColumn] = xlsString($data, $body, $biff8, $codepage);
                $pendingColumn = -1;
            }
            continue;
        }
        $isCell = $type === 0x00FD || $type === 0x0204 || $type === 0x00D6 || $type === 0x027E
            || $type === 0x00BD || $type === 0x0203 || $type === 0x0006 || $type === 0x0205;
        if (!$isCell || $size < 6) {
            continue;
        }
        $cellRow = ord($data[$body]) | (ord($data[$body + 1]) << 8);
        $column = ord($data[$body + 2]) | (ord($data[$body + 3]) << 8);
        if ($cellRow !== $row) {
            $flush();
            $row = $cellRow;
            $pendingColumn = -1;
        }
        if ($type === 0x00FD && $size >= 10) {                    // LABELSST
            $cells[$column] = $strings[unpack('V', $data, $body + 6)[1]] ?? '';
        } elseif ($type === 0x0204 || $type === 0x00D6) {         // LABEL, RSTRING
            $cells[$column] = xlsString($data, $body + 6, $biff8, $codepage);
        } elseif ($type === 0x027E && $size >= 10) {              // RK
            $setNumber($column, xlsRkValue(unpack('V', $data, $body + 6)[1]), unpack('v', $data, $body + 4)[1]);
        } elseif ($type === 0x00BD) {                             // MULRK: a run of (style, RK) cells
            for ($i = 0, $count = intdiv($size - 6, 6); $i < $count; $i++) {
                $setNumber($column + $i, xlsRkValue(unpack('V', $data, $body + 6 + $i * 6)[1]),
                    unpack('v', $data, $body + 4 + $i * 6)[1]);
            }
        } elseif ($type === 0x0203 && $size >= 14) {              // NUMBER
            $setNumber($column, unpack('e', $data, $body + 6)[1], unpack('v', $data, $body + 4)[1]);
        } elseif ($type === 0x0006 && $size >= 14) {              // FORMULA: use its cached result
            if (substr($data, $body + 12, 2) === "\xFF\xFF") {
                $kind = ord($data[$body + 6]);
                if ($kind === 0) {
                    $cells[$column] = '';
                    $pendingColumn = $column;
                } else {
                    $cells[$column] = $kind === 1 ? (ord($data[$body + 8]) ? 'TRUE' : 'FALSE') : '';
                }
            } else {
                $setNumber($column, unpack('e', $data, $body + 6)[1], unpack('v', $data, $body + 4)[1]);
            }
        } elseif ($type === 0x0205 && $size >= 8) {               // BOOLERR
            $cells[$column] = ord($data[$body + 7]) ? '' : (ord($data[$body + 6]) ? 'TRUE' : 'FALSE');
        }
    }
    $flush();
}

/**
 * The shared-string table. It spans the SST record and any CONTINUE records;
 * when a string's characters are split across records, the continuation starts
 * with a new flags byte saying whether the rest is 8-bit or UTF-16.
 */
function xlsSharedStrings(array $chunks): array
{
    $strings = [];
    $index = 0;
    $data = $chunks[0];
    $length = strlen($data);
    if ($length < 8) {
        return [];
    }
    $unique = unpack('V', $data, 4)[1];
    $pos = 8;
    $nextChunk = static function () use ($chunks, &$index, &$data, &$length, &$pos): bool {
        if (!isset($chunks[++$index])) {
            return false;
        }
        $data = $chunks[$index];
        $length = strlen($data);
        $pos = 0;
        return true;
    };
    for ($n = 0; $n < $unique; $n++) {
        if ($pos + 3 > $length && !$nextChunk()) {
            break;
        }
        $count = ord($data[$pos]) | (ord($data[$pos + 1]) << 8);
        $flags = ord($data[$pos + 2]);
        $pos += 3;
        $skip = 0;
        if ($flags & 0x08) {     // rich-text formatting runs follow the text
            $skip += 4 * (ord($data[$pos]) | (ord($data[$pos + 1]) << 8));
            $pos += 2;
        }
        if ($flags & 0x04) {     // phonetic data follows the text
            $skip += unpack('V', $data, $pos)[1];
            $pos += 4;
        }
        $wide = (bool) ($flags & 0x01);
        $text = '';
        while ($count > 0) {
            if ($pos >= $length) {
                if (!$nextChunk()) {
                    break 2;
                }
                $wide = (bool) (ord($data[0]) & 0x01);
                $pos = 1;
            }
            $take = $wide ? min($count, intdiv($length - $pos, 2)) : min($count, $length - $pos);
            if ($take <= 0) {
                $pos = $length;
                continue;
            }
            $bytes = $wide ? $take * 2 : $take;
            $text .= xlsDecode(substr($data, $pos, $bytes), $wide ? 'UTF-16LE' : 'ISO-8859-1');
            $pos += $bytes;
            $count -= $take;
        }
        while ($skip > 0) {
            if ($pos >= $length && !$nextChunk()) {
                break;
            }
            $take = min($skip, $length - $pos);
            $pos += $take;
            $skip -= $take;
        }
        $strings[] = $text;
    }
    return $strings;
}

/** A cell string: BIFF8 stores a length, a flags byte and 8-bit or UTF-16 text; BIFF5 plain bytes. */
function xlsString(string $data, int $offset, bool $biff8, string $codepage): string
{
    if ($offset + 2 > strlen($data)) {
        return '';
    }
    $count = unpack('v', $data, $offset)[1];
    if (!$biff8) {
        return xlsDecode(substr($data, $offset + 2, $count), $codepage);
    }
    $flags = ord($data[$offset + 2] ?? "\0");
    $start = $offset + 3 + (($flags & 0x08) ? 2 : 0) + (($flags & 0x04) ? 4 : 0);
    return ($flags & 0x01)
        ? xlsDecode(substr($data, $start, $count * 2), 'UTF-16LE')
        : xlsDecode(substr($data, $start, $count), 'ISO-8859-1');
}

function xlsDecode(string $bytes, string $from): string
{
    if ($bytes === '' || ($from !== 'UTF-16LE' && !preg_match('/[\x80-\xFF]/', $bytes))) {
        return $bytes;
    }
    if (function_exists('iconv')) {
        foreach ([$from, 'CP1252'] as $charset) {
            $text = @iconv($charset, 'UTF-8//IGNORE', $bytes);
            if ($text !== false) {
                return $text;
            }
            if ($from === 'UTF-16LE') {
                break;
            }
        }
    }
    if (function_exists('mb_convert_encoding')) {
        $charset = $from === 'UTF-16LE' || $from === 'ISO-8859-1' ? $from : 'Windows-1252';
        return (string) @mb_convert_encoding($bytes, 'UTF-8', $charset);
    }
    return (string) preg_replace('/[\x80-\xFF]/', '?', $from === 'UTF-16LE' ? str_replace("\0", '', $bytes) : $bytes);
}

/** An RK value: a 30-bit integer or the top 30 bits of a double, optionally divided by 100. */
function xlsRkValue(int $rk): float
{
    if ($rk & 0x02) {
        $value = ($rk >= 0x80000000 ? $rk - 0x100000000 : $rk) >> 2;
    } else {
        $value = unpack('e', pack('V2', 0, $rk & 0xFFFFFFFC))[1];
    }
    return ($rk & 0x01) ? $value / 100 : (float) $value;
}

/** Whole numbers without a decimal point (barcodes), others with up to 15 significant digits. */
function xlsNumberText(float $value): string
{
    if (is_nan($value) || is_infinite($value)) {
        return '';
    }
    if (floor($value) == $value && abs($value) < 1e15) {
        return sprintf('%.0F', $value);
    }
    return sprintf('%.15G', $value);
}

/** The first worksheet in tab order, per workbook.xml and its relationships. */
function xlsxFirstSheetPath(ZipArchive $zip): string
{
    $workbook = $zip->getFromName('xl/workbook.xml');
    $rels = $zip->getFromName('xl/_rels/workbook.xml.rels');
    if ($workbook !== false && $rels !== false
        && preg_match('/<(?:\w+:)?sheet\b[^>]*\br:id="([^"]+)"/', $workbook, $sheet)
        && preg_match('/<Relationship\b[^>]*\bId="' . preg_quote($sheet[1], '/') . '"[^>]*>/', $rels, $rel)
        && preg_match('/\bTarget="([^"]+)"/', $rel[0], $target)) {
        $path = ltrim($target[1], '/');
        $path = strpos($path, 'xl/') === 0 ? $path : 'xl/' . $path;
        if ($zip->locateName($path) !== false) {
            return $path;
        }
    }
    if ($zip->locateName('xl/worksheets/sheet1.xml') !== false) {
        return 'xl/worksheets/sheet1.xml';
    }
    for ($i = 0; $i < $zip->numFiles; $i++) {
        $name = (string) $zip->getNameIndex($i);
        if (preg_match('#^xl/worksheets/sheet\d+\.xml$#', $name)) {
            return $name;
        }
    }
    throw new RuntimeException('The Excel file contains no worksheet.');
}

function xlsxOpen(string $workbook, string $entry): XMLReader
{
    $reader = new XMLReader();
    if (!@$reader->open('zip://' . $workbook . '#' . $entry, null, LIBXML_NONET | LIBXML_COMPACT)) {
        throw new RuntimeException('The Excel file could not be read.');
    }
    return $reader;
}

/** The workbook's shared-string table (text of each <si>, without phonetic runs). */
function xlsxSharedStrings(string $workbook): array
{
    $reader = xlsxOpen($workbook, 'xl/sharedStrings.xml');
    $strings = [];
    $value = '';
    $inItem = false;
    $inText = false;
    $inPhonetic = false;
    try {
        while ($reader->read()) {
            $type = $reader->nodeType;
            if ($type === XMLReader::ELEMENT) {
                $name = $reader->localName;
                if ($name === 'si') {
                    $value = '';
                    $inItem = !$reader->isEmptyElement;
                    if (!$inItem) {
                        $strings[] = '';
                    }
                } elseif ($name === 'rPh') {
                    $inPhonetic = !$reader->isEmptyElement;
                } elseif ($name === 't' && $inItem && !$inPhonetic) {
                    $inText = !$reader->isEmptyElement;
                }
            } elseif ($type === XMLReader::END_ELEMENT) {
                $name = $reader->localName;
                if ($name === 't') {
                    $inText = false;
                } elseif ($name === 'rPh') {
                    $inPhonetic = false;
                } elseif ($name === 'si') {
                    $strings[] = $value;
                    $inItem = false;
                }
            } elseif ($inText && ($type === XMLReader::TEXT || $type === XMLReader::CDATA
                    || $type === XMLReader::WHITESPACE || $type === XMLReader::SIGNIFICANT_WHITESPACE)) {
                $value .= $reader->value;
            }
        }
    } finally {
        $reader->close();
    }
    return $strings;
}

/**
 * Streams a worksheet, calling $onRow with each row's cells (0-based, gaps
 * filled with '') and the set of column indexes whose cell was stored as a
 * number rather than text. Numbers in a zero-padded style ($zeroPad) are
 * returned as Excel displays them and are not flagged.
 */
function xlsxEachRow(string $workbook, string $sheet, array $strings, callable $onRow, array $zeroPad = []): void
{
    $reader = xlsxOpen($workbook, $sheet);
    $row = null;
    $numeric = [];
    $column = -1;
    $style = 0;
    $cellType = '';
    $value = '';
    $inValue = false;
    try {
        while ($reader->read()) {
            $type = $reader->nodeType;
            if ($type === XMLReader::ELEMENT) {
                $name = $reader->localName;
                if ($name === 'row') {
                    $row = [];
                    $numeric = [];
                    if ($reader->isEmptyElement) {
                        $onRow([], []);
                        $row = null;
                    }
                } elseif ($name === 'c' && $row !== null) {
                    $column = xlsxColumnIndex((string) $reader->getAttribute('r'), count($row));
                    $cellType = (string) $reader->getAttribute('t');
                    $style = (int) $reader->getAttribute('s');
                    $value = '';
                    if ($reader->isEmptyElement) {
                        $column = -1;
                    }
                } elseif (($name === 'v' || ($name === 't' && $cellType === 'inlineStr')) && $row !== null) {
                    $inValue = !$reader->isEmptyElement;
                }
            } elseif ($type === XMLReader::END_ELEMENT) {
                $name = $reader->localName;
                if ($name === 'v' || $name === 't') {
                    $inValue = false;
                } elseif ($name === 'c' && $row !== null && $column >= 0) {
                    if ($cellType === 's') {
                        $value = $value === '' ? '' : ($strings[(int) $value] ?? '');
                    } elseif ($cellType === 'e') {
                        $value = '';
                    } elseif (($cellType === '' || $cellType === 'n') && $value !== '') {
                        if (isset($zeroPad[$style])) {
                            $value = spreadsheetZeroPad($value, $zeroPad[$style]);
                        } else {
                            $numeric[$column] = true;
                        }
                    }
                    for ($i = count($row); $i < $column; $i++) {
                        $row[$i] = '';
                    }
                    $row[$column] = $value;
                    $column = -1;
                } elseif ($name === 'row' && $row !== null) {
                    ksort($row);
                    $onRow(array_values($row), $numeric);
                    $row = null;
                }
            } elseif ($inValue && ($type === XMLReader::TEXT || $type === XMLReader::CDATA
                    || $type === XMLReader::WHITESPACE || $type === XMLReader::SIGNIFICANT_WHITESPACE)) {
                $value .= $reader->value;
            }
        }
    } finally {
        $reader->close();
    }
}

/** "C12" -> 2. Cells without a reference take the next position. */
function xlsxColumnIndex(string $reference, int $next): int
{
    if (!preg_match('/^([A-Z]+)/', $reference, $letters)) {
        return $next;
    }
    $index = 0;
    foreach (str_split($letters[1]) as $letter) {
        $index = $index * 26 + (ord($letter) - 64);
    }
    return $index - 1;
}

/**
 * Rounds a spreadsheet number such as 249.750032 to centavos ("249.75"),
 * half up like the app. Text that is not a plain number is kept as it is.
 */
function catalogPriceFromCell(string $value): string
{
    $value = trim($value);
    // Excel shows numbers to 15 significant digits but saves the exact binary
    // value (20.255 is stored as 20.254999999999999); round from what it shows.
    if (is_numeric($value) && strlen((string) preg_replace('/\D/', '', $value)) > 15) {
        $value = sprintf('%.15G', (float) $value);
    }
    if (preg_match('/^[+-]?(\d+(\.\d*)?|\.\d+)[eE][+-]?\d+$/', $value)) {
        $value = sprintf('%.8F', (float) $value);
    }
    if (!preg_match('/^(-?)(\d*)(?:\.(\d*))?$/', $value, $parts) || ($parts[2] === '' && ($parts[3] ?? '') === '')) {
        return $value;
    }
    $fraction = str_pad($parts[3] ?? '', 3, '0');
    $cents = (int) ($parts[2] === '' ? '0' : $parts[2]) * 100 + (int) substr($fraction, 0, 2)
        + ((int) $fraction[2] >= 5 ? 1 : 0);
    return $parts[1] . intdiv($cents, 100) . '.' . str_pad((string) ($cents % 100), 2, '0', STR_PAD_LEFT);
}

function catalogItemFromRow(array $row, array $schema): ?array
{
    $barcode = preg_replace('/\s+/', '', catalogCell($row, $schema['barcode']));
    $name = catalogCell($row, $schema['name']);
    if ($barcode === '' || $name === '') {
        return null;
    }
    return [
        'barcode' => $barcode,
        'name' => $name,
        'uom' => catalogCell($row, $schema['uom']),
        'category' => catalogCell($row, $schema['category']),
        'price' => catalogCell($row, $schema['price']),
        'item_code' => catalogCell($row, $schema['item_code']),
    ];
}

function readCatalogItems(string $path): array
{
    if (!is_file($path)) {
        return [];
    }
    $handle = fopen($path, 'rb');
    if ($handle === false) {
        throw new RuntimeException('The product catalog could not be opened.');
    }
    try {
        $schema = catalogSchema($handle);
        if (!$schema) {
            return [];
        }
        $items = [];
        while (($row = fgetcsv($handle)) !== false) {
            $item = catalogItemFromRow($row, $schema);
            if ($item !== null) {
                $items[] = $item;
            }
        }
        return $items;
    } finally {
        fclose($handle);
    }
}

function catalogPage(string $query, int $page, int $pageSize): array
{
    $path = activeCatalogPath();
    if (!is_file($path)) {
        return ['items' => [], 'total' => 0, 'shown' => 0, 'page' => 1, 'pages' => 1];
    }
    $handle = fopen($path, 'rb');
    if ($handle === false) {
        throw new RuntimeException('The product catalog could not be opened.');
    }
    try {
        $schema = catalogSchema($handle);
        if (!$schema) {
            return ['items' => [], 'total' => 0, 'shown' => 0, 'page' => 1, 'pages' => 1];
        }
        $query = trim($query);
        $matches = [];
        $total = 0;
        $offset = max(0, ($page - 1) * $pageSize);
        while (($row = fgetcsv($handle)) !== false) {
            // Cheap reject on the raw row first: a live-search keystroke scans the
            // whole catalog, and parsing every non-matching row is the expensive part.
            if ($query !== '' && stripos(implode("\x1F", $row), $query) === false) {
                continue;
            }
            $item = catalogItemFromRow($row, $schema);
            if ($item === null) {
                continue;
            }
            if ($query !== '' && stripos(implode(' ', $item), $query) === false) {
                continue;
            }
            if ($total >= $offset && count($matches) < $pageSize) {
                $matches[] = $item;
            }
            $total++;
        }
        $pages = max(1, (int) ceil($total / $pageSize));
        return [
            'items' => $matches,
            'total' => $total,
            'shown' => count($matches),
            'page' => min($page, $pages),
            'pages' => $pages,
        ];
    } finally {
        fclose($handle);
    }
}

function findCatalogItem(string $barcode): ?array
{
    $path = activeCatalogPath();
    if (!is_file($path)) {
        return null;
    }
    $handle = fopen($path, 'rb');
    if ($handle === false) {
        throw new RuntimeException('The product catalog could not be opened.');
    }
    try {
        $schema = catalogSchema($handle);
        while ($schema && ($row = fgetcsv($handle)) !== false) {
            $item = catalogItemFromRow($row, $schema);
            if ($item !== null && $item['barcode'] === $barcode) {
                return $item;
            }
        }
        return null;
    } finally {
        fclose($handle);
    }
}

function writeCatalogItems(string $path, array $items): void
{
    $staged = __DIR__ . '/.catalog-' . bin2hex(random_bytes(8)) . '.tmp';
    $handle = fopen($staged, 'wb');
    if ($handle === false) {
        throw new RuntimeException('The catalog update could not be staged.');
    }
    try {
        fputcsv($handle, ['barcode', 'name', 'uom', 'category', 'price', 'item_code']);
        foreach ($items as $item) {
            fputcsv($handle, [
                $item['barcode'],
                $item['name'],
                $item['uom'],
                $item['category'],
                $item['price'],
                $item['item_code'],
            ]);
        }
    } finally {
        fclose($handle);
    }
    @chmod($staged, 0644);
    if (!@rename($staged, $path)) {
        @unlink($path);
        if (!@rename($staged, $path)) {
            @unlink($staged);
            throw new RuntimeException('The catalog file could not be replaced.');
        }
    }
    $other = substr($path, -4) === '.csv' ? __DIR__ . '/catalog.txt' : __DIR__ . '/catalog.csv';
    @unlink($other);
}

function catalogItemFromInput(array $input): array
{
    $barcode = preg_replace('/\s+/', '', cleanCatalogValue((string) ($input['barcode'] ?? '')));
    $name = cleanCatalogValue((string) ($input['name'] ?? ''));
    $uom = cleanCatalogValue((string) ($input['uom'] ?? ''));
    $category = cleanCatalogValue((string) ($input['category'] ?? ''));
    $itemCode = cleanCatalogValue((string) ($input['item_code'] ?? ''));
    $rawPrice = cleanCatalogValue((string) ($input['price'] ?? ''));
    if ($barcode === '' || $name === '' || $rawPrice === '' || !is_numeric($rawPrice) || (float) $rawPrice < 0) {
        throw new RuntimeException('Enter a barcode, product name, and valid non-negative price.');
    }
    $price = number_format((float) $rawPrice, 2, '.', '');
    $item = compact('barcode', 'name', 'uom', 'category', 'price');
    $item['item_code'] = $itemCode;
    return $item;
}

function writeCatalogItemLine($handle, array $item): void
{
    fputcsv($handle, [
        $item['barcode'],
        $item['name'],
        $item['uom'],
        $item['category'],
        $item['price'],
        $item['item_code'],
    ]);
}

function mutateCatalogItem(string $barcode, ?array $replacement): bool
{
    $path = activeCatalogPath();
    $staged = __DIR__ . '/.catalog-' . bin2hex(random_bytes(8)) . '.tmp';
    $out = fopen($staged, 'wb');
    if ($out === false) {
        throw new RuntimeException('The catalog update could not be staged.');
    }
    $found = false;
    try {
        fputcsv($out, ['barcode', 'name', 'uom', 'category', 'price', 'item_code']);
        if (is_file($path)) {
            $in = fopen($path, 'rb');
            if ($in === false) {
                throw new RuntimeException('The product catalog could not be opened.');
            }
            try {
                $schema = catalogSchema($in);
                while ($schema && ($row = fgetcsv($in)) !== false) {
                    $existing = catalogItemFromRow($row, $schema);
                    if ($existing === null) {
                        continue;
                    }
                    if ($existing['barcode'] === $barcode) {
                        if (!$found && $replacement !== null) {
                            writeCatalogItemLine($out, $replacement);
                        }
                        $found = true;
                        continue;
                    }
                    writeCatalogItemLine($out, $existing);
                }
            } finally {
                fclose($in);
            }
        }
        if (!$found && $replacement !== null) {
            writeCatalogItemLine($out, $replacement);
        }
    } finally {
        fclose($out);
    }
    @chmod($staged, 0644);
    if (!@rename($staged, $path)) {
        @unlink($path);
        if (!@rename($staged, $path)) {
            @unlink($staged);
            throw new RuntimeException('The catalog file could not be replaced.');
        }
    }
    $other = substr($path, -4) === '.csv' ? __DIR__ . '/catalog.txt' : __DIR__ . '/catalog.csv';
    @unlink($other);
    return $found;
}

function upsertCatalogItem(array $input): bool
{
    $item = catalogItemFromInput($input);
    return mutateCatalogItem($item['barcode'], $item);
}

function deleteCatalogItem(string $barcode): bool
{
    $barcode = preg_replace('/\s+/', '', cleanCatalogValue($barcode));
    if ($barcode === '') {
        throw new RuntimeException('Choose a valid catalog item.');
    }
    return mutateCatalogItem($barcode, null);
}

/* --- Promos & special prices ------------------------------------------------
   Kept in promos.csv, separate from the catalog, so a fresh catalog upload never
   wipes them. Devices download the file as-is; "promo" and "special" behave the
   same and only change the label. Dates are inclusive Y-m-d; blank = open-ended. */

const PROMO_COLUMNS = ['id', 'barcode', 'label', 'type', 'value', 'buy', 'take', 'start', 'end'];

function promosFile(): string
{
    return __DIR__ . '/promos.csv';
}

function promoTypeLabels(): array
{
    return [
        'percent' => 'Percent off',
        'amount' => 'Amount off',
        'fixed' => 'Fixed promo price',
        'buy_take' => 'Buy X Take Y',
    ];
}

function normalizePromoType(string $value): string
{
    $v = str_replace(['_', '-'], ' ', strtolower(trim($value)));
    $aliases = [
        'percent' => ['percent', 'percent off', '%', 'pct', 'discount'],
        'amount' => ['amount', 'amount off', 'less', 'peso off'],
        'fixed' => ['fixed', 'fixed price', 'price', 'promo price', 'special price'],
        'buy_take' => ['buy take', 'buy x take y', 'bxty', 'b1t1', 'buy1take1'],
    ];
    foreach ($aliases as $key => $names) {
        if (in_array($v, $names, true)) {
            return $key;
        }
    }
    return '';
}

function normalizePromoLabel(string $value): string
{
    $v = strtolower(trim($value));
    return (strpos($v, 'special') === 0 || $v === 'sp') ? 'special' : 'promo';
}

/** Y-m-d, or the M/D/YYYY that Excel saves; '' for blank, null when not a date. */
function normalizePromoDate(string $value): ?string
{
    $v = trim($value);
    if ($v === '') {
        return '';
    }
    foreach (['!Y-m-d', '!n/j/Y', '!Y/n/j'] as $format) {
        $date = DateTime::createFromFormat($format, $v);
        $errors = DateTime::getLastErrors();
        if ($date && (!$errors || ($errors['warning_count'] === 0 && $errors['error_count'] === 0))) {
            return $date->format('Y-m-d');
        }
    }
    return null;
}

/**
 * Validates and normalizes one promo from form fields or CSV cells.
 * Returns [promo|null, error message].
 */
function validatePromo(array $fields): array
{
    $get = static function (string $key) use ($fields): string {
        return trim(str_replace(["\r", "\n"], ' ', (string) ($fields[$key] ?? '')));
    };
    $barcode = preg_replace('/\s+/', '', $get('barcode'));
    if ($barcode === '') {
        return [null, 'Enter the product barcode.'];
    }
    $type = normalizePromoType($get('type'));
    if ($type === '') {
        return [null, 'Choose a promo type.'];
    }
    $start = normalizePromoDate($get('start'));
    $end = normalizePromoDate($get('end'));
    if ($start === null || $end === null) {
        return [null, 'Enter dates as YYYY-MM-DD.'];
    }
    if ($start !== '' && $end !== '' && $end < $start) {
        return [null, 'The end date can\'t be before the start date.'];
    }

    $value = '';
    $buy = '';
    $take = '';
    $raw = str_replace([',', "\u{20B1}", '%', ' '], '', $get('value'));
    if ($type === 'buy_take') {
        $buyQty = (int) $get('buy');
        $takeQty = (int) $get('take');
        if ($buyQty < 1 || $buyQty > 999 || $takeQty < 1 || $takeQty > 999) {
            return [null, 'Enter how many to buy and how many they take (1 or more).'];
        }
        $buy = (string) $buyQty;
        $take = (string) $takeQty;
    } elseif (!is_numeric($raw)) {
        return [null, 'Enter the promo value.'];
    } elseif ($type === 'percent') {
        $percent = round((float) $raw, 2);
        if ($percent <= 0 || $percent > 100) {
            return [null, 'Percent off must be between 0 and 100.'];
        }
        $value = rtrim(rtrim(number_format($percent, 2, '.', ''), '0'), '.');
    } else {
        $amount = round((float) $raw, 2);
        if ($amount < 0 || ($type === 'amount' && $amount == 0)) {
            return [null, 'Enter an amount greater than zero.'];
        }
        $value = number_format($amount, 2, '.', '');
    }

    $id = preg_replace('/[^a-f0-9]/', '', strtolower($get('id')));
    return [[
        'id' => $id !== '' ? $id : bin2hex(random_bytes(4)),
        'barcode' => $barcode,
        'label' => normalizePromoLabel($get('label')),
        'type' => $type,
        'value' => $value,
        'buy' => $buy,
        'take' => $take,
        'start' => $start,
        'end' => $end,
    ], ''];
}

/** Parses a promos CSV by header name. Returns [promos, skipped row numbers]. */
function parsePromosCsv(string $path): array
{
    $handle = fopen($path, 'rb');
    if ($handle === false) {
        throw new RuntimeException('The promos file could not be opened.');
    }
    try {
        $header = fgetcsv($handle);
        if ($header === false) {
            return [[], []];
        }
        $headers = array_map('normalizeCatalogHeader', $header);
        $columns = [
            'id' => catalogColumn($headers, ['id']),
            'barcode' => catalogColumn($headers, ['barcode', 'bar code', 'upc', 'ean', 'code']),
            'label' => catalogColumn($headers, ['label', 'kind']),
            'type' => catalogColumn($headers, ['type', 'promo type']),
            'value' => catalogColumn($headers, ['value', 'amount', 'percent', 'price']),
            'buy' => catalogColumn($headers, ['buy', 'buy qty']),
            'take' => catalogColumn($headers, ['take', 'take qty', 'get']),
            'start' => catalogColumn($headers, ['start', 'start date', 'from']),
            'end' => catalogColumn($headers, ['end', 'end date', 'until', 'to']),
        ];
        if ($columns['barcode'] < 0 || $columns['type'] < 0) {
            throw new RuntimeException('The promos file must include barcode and type columns.');
        }
        $promos = [];
        $skipped = [];
        $line = 1;
        while (($row = fgetcsv($handle)) !== false) {
            $line++;
            if ($row === [null] || implode('', $row) === '') {
                continue;
            }
            $fields = [];
            foreach ($columns as $key => $index) {
                $fields[$key] = catalogCell($row, $index);
            }
            [$promo] = validatePromo($fields);
            if ($promo === null) {
                $skipped[] = $line;
            } else {
                $promos[] = $promo;
            }
        }
        return [$promos, $skipped];
    } finally {
        fclose($handle);
    }
}

function readPromos(): array
{
    if (!is_file(promosFile())) {
        return [];
    }
    [$promos] = parsePromosCsv(promosFile());
    return $promos;
}

function writePromos(array $promos): void
{
    $staged = __DIR__ . '/.promos-' . bin2hex(random_bytes(8)) . '.tmp';
    $handle = fopen($staged, 'wb');
    if ($handle === false) {
        throw new RuntimeException('The promos update could not be staged.');
    }
    try {
        fputcsv($handle, PROMO_COLUMNS);
        foreach ($promos as $promo) {
            $row = [];
            foreach (PROMO_COLUMNS as $column) {
                $row[] = (string) ($promo[$column] ?? '');
            }
            fputcsv($handle, $row);
        }
    } finally {
        fclose($handle);
    }
    @chmod($staged, 0644);
    if (!@rename($staged, promosFile())) {
        @unlink(promosFile());
        if (!@rename($staged, promosFile())) {
            @unlink($staged);
            throw new RuntimeException('The promos file could not be replaced.');
        }
    }
}

function promoStatus(array $promo, string $today): string
{
    if ($promo['end'] !== '' && $promo['end'] < $today) {
        return 'ended';
    }
    if ($promo['start'] !== '' && $promo['start'] > $today) {
        return 'scheduled';
    }
    return 'active';
}

function promoLabelText(array $promo): string
{
    return $promo['label'] === 'special' ? 'SPECIAL PRICE' : 'PROMO';
}

function promoDealText(array $promo): string
{
    switch ($promo['type']) {
        case 'percent':
            return $promo['value'] . '% OFF';
        case 'amount':
            return "\u{20B1}" . number_format((float) $promo['value'], 2) . ' OFF';
        case 'fixed':
            return "\u{20B1}" . number_format((float) $promo['value'], 2);
        default:
            return 'BUY ' . $promo['buy'] . ' TAKE ' . $promo['take'];
    }
}

function promoDateLabel(string $date): string
{
    return $date === '' ? '' : date('M j, Y', (int) strtotime($date));
}

/** Catalog name and price for each barcode, found in one pass over the catalog. */
function catalogLookup(array $barcodes): array
{
    $wanted = array_fill_keys($barcodes, true);
    $found = [];
    $path = activeCatalogPath();
    if (!$wanted || !is_file($path)) {
        return $found;
    }
    $handle = fopen($path, 'rb');
    if ($handle === false) {
        return $found;
    }
    try {
        $schema = catalogSchema($handle);
        while ($schema && count($found) < count($wanted) && ($row = fgetcsv($handle)) !== false) {
            $item = catalogItemFromRow($row, $schema);
            if ($item !== null && isset($wanted[$item['barcode']]) && !isset($found[$item['barcode']])) {
                $found[$item['barcode']] = ['name' => $item['name'], 'price' => $item['price']];
            }
        }
    } finally {
        fclose($handle);
    }
    return $found;
}

/* --- Design ------------------------------------------------------------------
   The same look settings as the app's Admin screen, pushed to every scanner that
   syncs with this server. design.json holds only the settings the server manages;
   anything left out stays as each scanner has it. Defaults mirror the app's. */

function designFile(): string
{
    return __DIR__ . '/design.json';
}

function designFields(): array
{
    return [
        // Where the price check puts the name, barcode and price, and how it draws
        // an active promo. The keys are the app's (PriceScreen.LAYOUTS, PROMO_STYLES).
        'check_layout' => ['label' => 'Layout', 'type' => 'choice', 'default' => 'center', 'group' => 'check', 'options' => [
            'center' => 'Centered',
            'left' => 'Left aligned',
            'split' => 'Split',
            'price_first' => 'Price first',
        ]],
        'promo_style' => ['label' => 'Promo style', 'type' => 'choice', 'default' => 'classic', 'group' => 'check', 'options' => [
            'classic' => 'Classic banner',
            'sticker' => 'Sticker',
            'ribbon' => 'Corner ribbon',
            'poster' => 'Poster block',
            'tag' => 'Price tag',
        ]],
        'check_bg_color' => ['label' => 'Background', 'type' => 'color', 'default' => '#FDE047', 'group' => 'check'],
        'check_name_color' => ['label' => 'Product name', 'type' => 'color', 'default' => '#2563EB', 'group' => 'check'],
        'check_meta_color' => ['label' => 'Barcode', 'type' => 'color', 'default' => '#334155', 'group' => 'check'],
        'check_price_color' => ['label' => 'Price', 'type' => 'color', 'default' => '#DC2626', 'group' => 'check'],
        'scan_prompt_color' => ['label' => '"Scan here" prompt', 'type' => 'color', 'default' => '#00E676', 'group' => 'idle'],
        'logo_scale_percent' => ['label' => 'Logo size', 'type' => 'range', 'default' => 100, 'min' => 50, 'max' => 250, 'step' => 5, 'group' => 'idle'],
        'store_name' => ['label' => 'Store name over videos', 'type' => 'text', 'default' => '', 'group' => 'idle'],
        'video_shows_logo' => ['label' => 'Show the logo instead of the store name', 'type' => 'bool', 'default' => false, 'group' => 'idle'],
        'marquee_bg_color' => ['label' => 'Background', 'type' => 'color', 'default' => '#000000', 'group' => 'marquee'],
        'marquee_text_color' => ['label' => 'Text', 'type' => 'color', 'default' => '#FFFFFF', 'group' => 'marquee'],
        'image_display_seconds' => ['label' => 'Each image shows for', 'type' => 'int', 'default' => 12, 'min' => 1, 'max' => 86400, 'unit' => 'seconds', 'group' => 'timing'],
        'video_play_seconds' => ['label' => 'Each video plays for', 'type' => 'int', 'default' => 0, 'min' => 0, 'max' => 86400, 'unit' => 'seconds (0 = whole video)', 'group' => 'timing'],
    ];
}

/** Normalizes one design value; null when it isn't valid for the field. */
function normalizeDesignValue(array $field, $value)
{
    switch ($field['type']) {
        case 'color':
            $v = strtoupper(trim((string) $value));
            if ($v !== '' && $v[0] !== '#') {
                $v = '#' . $v;
            }
            return preg_match('/^#[0-9A-F]{6}$/', $v) ? $v : null;
        case 'range':
        case 'int':
            if (!is_numeric($value)) {
                return null;
            }
            $n = (int) round((float) $value);
            return ($n < $field['min'] || $n > $field['max']) ? null : $n;
        case 'bool':
            return in_array($value, [true, 1, '1', 'true', 'on'], true);
        case 'choice':
            $v = strtolower(trim((string) $value));
            return isset($field['options'][$v]) ? $v : null;
        default:
            $text = trim(str_replace(["\r", "\n"], ' ', (string) $value));
            return function_exists('mb_substr') ? mb_substr($text, 0, 60) : substr($text, 0, 60);
    }
}

/** The settings the server currently manages, as key => value. */
function readDesign(): array
{
    if (!is_file(designFile())) {
        return [];
    }
    $data = json_decode((string) file_get_contents(designFile()), true);
    if (!is_array($data)) {
        return [];
    }
    $design = [];
    foreach (designFields() as $key => $field) {
        if (array_key_exists($key, $data)) {
            $value = normalizeDesignValue($field, $data[$key]);
            if ($value !== null) {
                $design[$key] = $value;
            }
        }
    }
    return $design;
}

/** Always writes the file, even with nothing managed, so scanners see the change. */
function writeDesign(array $design): void
{
    $json = json_encode(['version' => 1] + $design,
        JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    $staged = __DIR__ . '/.design-' . bin2hex(random_bytes(8)) . '.tmp';
    if (file_put_contents($staged, $json . "\n", LOCK_EX) === false) {
        throw new RuntimeException('The design could not be saved. Check the folder permissions.');
    }
    @chmod($staged, 0644);
    if (!@rename($staged, designFile())) {
        @unlink(designFile());
        if (!@rename($staged, designFile())) {
            @unlink($staged);
            throw new RuntimeException('The design file could not be replaced.');
        }
    }
}

// ============================================================================
// Data source: products read straight from the store's POS database, or from
// a file the POS exports, and written to catalog.csv on a schedule, exactly
// as an upload would be. Optional and off until set up on the Data source
// tab; scanners need nothing new. The scheduler runs this file as
// "php index.php --catalog-source-sync" (see catalogSourceCli).
// ============================================================================

/** Every kind of source and what its form asks for. "needs" is what the server must have installed. */
function sourceKinds(): array
{
    $odbc = ' This uses ODBC, which the Windows server includes; the Linux server cannot use it.';
    return [
        'mysql' => ['label' => 'MySQL / MariaDB', 'about' => 'Most PC-based POS systems', 'port' => 3306,
            'fields' => ['host', 'port', 'database', 'login'], 'needs' => 'Nothing to install.'],
        'sqlserver' => ['label' => 'Microsoft SQL Server', 'about' => 'Includes SAP Business One on SQL Server', 'port' => 1433,
            'fields' => ['host', 'instance', 'port', 'database', 'login', 'windows_auth', 'driver', 'encrypt'],
            'needs' => 'Uses the SQL Server driver built into Windows, or ODBC Driver 17/18 for SQL Server when it is installed.'
                . ' Works with SQL Server 2005 and newer.' . $odbc],
        'postgres' => ['label' => 'PostgreSQL', 'about' => 'Odoo and other open-source POS', 'port' => 5432,
            'fields' => ['host', 'port', 'database', 'login'], 'needs' => 'Nothing to install.'],
        'hana' => ['label' => 'SAP HANA', 'about' => 'SAP Business One on HANA, S/4HANA', 'port' => 30015,
            'fields' => ['host', 'port', 'database', 'login', 'driver', 'encrypt'],
            'needs' => 'Install the 64-bit SAP HANA client on this server; it adds the HDBODBC driver. For SAP Business One the port is usually 30015 or 30013.' . $odbc],
        'oracle' => ['label' => 'Oracle Database', 'about' => 'Oracle Retail and other Oracle systems', 'port' => 1521,
            'fields' => ['host', 'port', 'database', 'login', 'driver'],
            'needs' => 'Install the 64-bit Oracle Instant Client with its ODBC package on this server, then pick its driver below.' . $odbc],
        'firebird' => ['label' => 'Firebird / InterBase', 'about' => 'Common in older PC POS software', 'port' => 3050,
            'fields' => ['host', 'port', 'database', 'login', 'driver'],
            'needs' => 'Install the 64-bit Firebird ODBC driver on this server.' . $odbc],
        'db2' => ['label' => 'IBM Db2', 'about' => 'IBM and Toshiba retail systems', 'port' => 50000,
            'fields' => ['host', 'port', 'database', 'login', 'driver'],
            'needs' => 'Install the 64-bit IBM Data Server Driver (ODBC) on this server.' . $odbc],
        'access' => ['label' => 'Microsoft Access file', 'about' => 'An .mdb or .accdb file', 'port' => 0,
            'fields' => ['file', 'password', 'driver'],
            'needs' => 'Install the 64-bit Microsoft Access Database Engine on this server.' . $odbc],
        'sqlite' => ['label' => 'SQLite file', 'about' => 'A .db or .sqlite file', 'port' => 0,
            'fields' => ['file'], 'needs' => 'Nothing to install.'],
        'odbc' => ['label' => 'Other database (ODBC)', 'about' => 'Sybase, Informix, Pervasive and any other with an ODBC driver', 'port' => 0,
            'fields' => ['dsn', 'login'],
            'needs' => 'Install the database\'s 64-bit ODBC driver on this server and create a System DSN for it, or type a full connection string.' . $odbc],
        'file' => ['label' => 'File exported by the POS', 'about' => 'CSV, TXT or Excel in a folder', 'port' => 0,
            'fields' => ['file'],
            'needs' => 'For POS software that exports its item list to a file. The file must be on this server or on a share it can read.'],
        'url' => ['label' => 'Web address', 'about' => 'A CSV or JSON feed from a cloud POS', 'port' => 0,
            'fields' => ['url', 'header', 'format', 'records'],
            'needs' => 'For cloud POS systems and other servers that publish the item list at a web address.'],
    ];
}

/** Kinds read with SQL (everything except a file and a web address). */
function sourceIsDatabase(string $kind): bool
{
    return !in_array($kind, ['file', 'url'], true);
}

/** ODBC drivers tried, in order, when the Driver box is left blank. */
function sourceDriverCandidates(string $kind): array
{
    switch ($kind) {
        case 'sqlserver':
            return ['ODBC Driver 18 for SQL Server', 'ODBC Driver 17 for SQL Server', 'ODBC Driver 13 for SQL Server',
                'SQL Server Native Client 11.0', 'SQL Server'];
        case 'hana':
            return ['HDBODBC'];
        case 'db2':
            return array_merge(['IBM DB2 ODBC DRIVER'], sourceInstalledDrivers('/db2/i'));
        case 'access':
            return ['Microsoft Access Driver (*.mdb, *.accdb)'];
        case 'firebird':
            return array_merge(['Firebird/InterBase(r) driver'], sourceInstalledDrivers('/firebird/i'));
        case 'oracle':
            return sourceInstalledDrivers('/^oracle/i');
    }
    return [];
}

/** Names of the ODBC drivers installed on this (Windows) server, optionally only those matching $pattern. */
function sourceInstalledDrivers(string $pattern = ''): array
{
    static $names = null;
    if ($names === null) {
        $names = [];
        if (DIRECTORY_SEPARATOR === '\\' && functionAvailable('shell_exec')) {
            $output = (string) @shell_exec('reg query "HKLM\\SOFTWARE\\ODBC\\ODBCINST.INI\\ODBC Drivers" 2>NUL');
            if (preg_match_all('/^\s{2,}(.+?)\s{2,}REG_SZ\s{2,}Installed\s*$/mi', $output, $found)) {
                $names = array_values(array_unique($found[1]));
            }
        }
    }
    return $pattern === '' ? $names : array_values(preg_grep($pattern, $names));
}

/** [ready, why not]: whether this server's PHP can use a kind of source at all. */
function sourceKindReady(string $kind): array
{
    $drivers = class_exists('PDO') ? PDO::getAvailableDrivers() : [];
    $odbc = in_array('odbc', $drivers, true);
    $missing = 'This server\'s PHP cannot connect to it (no ODBC support). Use the Windows server.';
    switch ($kind) {
        case 'mysql':
            return [in_array('mysql', $drivers, true), 'This server\'s PHP is missing the pdo_mysql extension.'];
        case 'postgres':
            return [in_array('pgsql', $drivers, true), 'This server\'s PHP is missing the pdo_pgsql extension.'];
        case 'sqlite':
            return [in_array('sqlite', $drivers, true), 'This server\'s PHP is missing the pdo_sqlite extension.'];
        case 'sqlserver':
            return [$odbc || in_array('sqlsrv', $drivers, true) || in_array('dblib', $drivers, true), $missing];
        case 'oracle':
            return [$odbc || in_array('oci', $drivers, true), $missing];
        case 'firebird':
            return [$odbc || in_array('firebird', $drivers, true), $missing];
        case 'hana':
        case 'db2':
        case 'access':
        case 'odbc':
            return [$odbc, $missing];
        case 'file':
            return [true, ''];
        case 'url':
            return [(bool) ini_get('allow_url_fopen'), 'allow_url_fopen is turned off in this server\'s php.ini.'];
    }
    return [false, 'Unknown kind of source.'];
}

function sourceSchedules(): array
{
    return [
        '5' => 'Every 5 minutes',
        '15' => 'Every 15 minutes',
        '30' => 'Every 30 minutes',
        '60' => 'Every hour',
        '180' => 'Every 3 hours',
        '720' => 'Every 12 hours',
        'daily' => 'Once a day',
        'manual' => 'Only when I press Sync now',
    ];
}

function sourceDefaults(): array
{
    return [
        'enabled' => false, 'kind' => 'mysql',
        'host' => '', 'port' => '', 'instance' => '', 'database' => '', 'username' => '', 'password' => '',
        'windows_auth' => false, 'driver' => '', 'encrypt' => false, 'dsn' => '', 'file' => '',
        'url' => '', 'header' => '', 'format' => 'csv', 'records' => '',
        'read' => 'table', 'table' => '', 'filter' => '', 'query' => '',
        'map' => array_fill_keys(array_keys(catalogFieldLabels()), ''),
        'price_unit' => 'peso', 'add_vat' => false, 'encoding' => 'auto',
        'schedule' => '15', 'daily_at' => '06:00', 'mode' => 'replace', 'notify' => true, 'max_drop' => 50,
        'saved_at' => 0,
    ];
}

/** Reads a "<?php exit; ?>" + JSON file (it shows nothing if the web server runs it). */
function sourceReadFile(string $path): array
{
    $raw = is_file($path) ? (string) @file_get_contents($path) : '';
    $data = json_decode((string) preg_replace('/^<\?php[^\n]*\n/', '', $raw), true);
    return is_array($data) ? $data : [];
}

function sourceWriteFile(string $path, array $data): void
{
    $json = json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
    $staged = __DIR__ . '/.catalog-source-' . bin2hex(random_bytes(6)) . '.tmp';
    if ($json === false || file_put_contents($staged, "<?php exit; ?>\n" . $json . "\n", LOCK_EX) === false) {
        throw new RuntimeException('The data source settings could not be saved. Check the folder permissions.');
    }
    @chmod($staged, 0640);
    if (!@rename($staged, $path)) {
        @unlink($path);
        if (!@rename($staged, $path)) {
            @unlink($staged);
            throw new RuntimeException('The data source settings file could not be replaced.');
        }
    }
}

/** The saved settings, password included: never send them to a browser as they are. */
function sourceConfig(): array
{
    $defaults = sourceDefaults();
    $config = array_replace($defaults, array_intersect_key(sourceReadFile(SOURCE_CONFIG_FILE), $defaults));
    $config['map'] = array_replace($defaults['map'], array_intersect_key(is_array($config['map']) ? $config['map'] : [], $defaults['map']));
    if (!isset(sourceKinds()[$config['kind']])) {
        $config['kind'] = $defaults['kind'];
    }
    return $config;
}

/** What the last syncs did: last_try, last_ok, last_change, ok, message, items, history... */
function sourceState(): array
{
    return sourceReadFile(SOURCE_STATE_FILE);
}

/** One line of text from a form, with no control characters; $max characters at most. */
function sourceText(array $input, string $key, int $max, string $what): string
{
    $value = trim((string) ($input[$key] ?? ''));
    if (preg_match('/[\x00-\x1F\x7F]/', $value)) {
        throw new RuntimeException($what . ' cannot contain line breaks or control characters.');
    }
    if (strlen($value) > $max) {
        throw new RuntimeException($what . ' is too long (at most ' . $max . ' characters).');
    }
    return $value;
}

/**
 * Builds settings from the Data source form, on top of the saved ones: a blank
 * password or header keeps the saved one. Checks the format of every value;
 * sourceRequire() checks that the needed ones are filled in.
 */
function sourceConfigFromInput(array $in, array $old): array
{
    $c = sourceDefaults();
    $c['saved_at'] = $old['saved_at'];
    $kind = (string) ($in['kind'] ?? '');
    if (!isset(sourceKinds()[$kind])) {
        throw new RuntimeException('Choose where the products come from.');
    }
    $c['kind'] = $kind;
    $c['enabled'] = !empty($in['enabled']);

    $host = sourceText($in, 'host', 255, 'The server address');
    // "POS-PC\SQLEXPRESS" typed as the address: the part after the backslash is the instance.
    if (strpos($host, '\\') !== false) {
        [$host, $instance] = explode('\\', $host, 2);
        $in['instance'] = $instance;
    }
    if ($host !== '' && !preg_match('/^[A-Za-z0-9._:\[\]%-]+$/', $host)) {
        throw new RuntimeException('The server address can only be a computer name or an IP address, like 192.168.1.20 or POS-PC.');
    }
    $c['host'] = $host;
    $port = trim((string) ($in['port'] ?? ''));
    if ($port !== '' && (!ctype_digit($port) || (int) $port < 1 || (int) $port > 65535)) {
        throw new RuntimeException('The port must be a number from 1 to 65535, or blank for the usual one.');
    }
    $c['port'] = $port;
    $c['instance'] = sourceText($in, 'instance', 64, 'The instance name');
    if ($c['instance'] !== '' && !preg_match('/^[A-Za-z0-9_$#-]+$/', $c['instance'])) {
        throw new RuntimeException('The instance name can only have letters, numbers, _ and $.');
    }
    $c['database'] = sourceText($in, 'database', 300, 'The database name');
    $c['username'] = sourceText($in, 'username', 128, 'The user name');
    $password = (string) ($in['password'] ?? '');
    $c['password'] = !empty($in['password_clear']) ? '' : ($password !== '' ? $password : $old['password']);
    if (strlen($c['password']) > 256) {
        throw new RuntimeException('The password is too long.');
    }
    $c['windows_auth'] = !empty($in['windows_auth']);
    $c['encrypt'] = !empty($in['encrypt']);
    $c['driver'] = sourceText($in, 'driver', 200, 'The ODBC driver name');
    if (strpbrk($c['driver'], '{};') !== false) {
        throw new RuntimeException('Type the ODBC driver name without { } or ;.');
    }
    foreach (['host', 'database', 'username', 'instance'] as $key) {
        // These go into a connection string, where ; starts the next setting.
        if (strpos($c[$key], ';') !== false && $c['kind'] !== 'odbc') {
            throw new RuntimeException('The ' . ($key === 'username' ? 'user name' : $key) . ' cannot contain ;.');
        }
    }
    $c['dsn'] = sourceText($in, 'dsn', 1000, 'The data source name or connection string');
    $c['file'] = sourceText($in, 'file', 500, 'The file path');
    $c['url'] = sourceText($in, 'url', 1000, 'The web address');
    if ($c['url'] !== '' && !preg_match('#^https?://[^\s/]+#i', $c['url'])) {
        throw new RuntimeException('The web address must start with http:// or https://.');
    }
    $header = sourceText($in, 'header', 2000, 'The request header');
    $c['header'] = !empty($in['header_clear']) ? '' : ($header !== '' ? $header : $old['header']);
    if ($c['header'] !== '' && !preg_match('/^[A-Za-z0-9-]+:\s*\S/', $c['header'])) {
        throw new RuntimeException('Type the header as Name: value, for example Authorization: Bearer abc123.');
    }
    $c['format'] = ($in['format'] ?? '') === 'json' ? 'json' : 'csv';
    $c['records'] = sourceText($in, 'records', 200, 'Where the items are in the JSON');

    $c['read'] = ($in['read'] ?? '') === 'query' ? 'query' : 'table';
    $c['table'] = sourceText($in, 'table', 200, 'The table name');
    if (strpbrk($c['table'], ';\'') !== false) {
        throw new RuntimeException('The table name cannot contain ; or quotes.');
    }
    $c['filter'] = sourceText($in, 'filter', 1000, 'The filter');
    $query = trim(str_replace("\r\n", "\n", (string) ($in['query'] ?? '')));
    if (strlen($query) > 20000) {
        throw new RuntimeException('The query is too long (at most 20,000 characters).');
    }
    $c['query'] = $query;
    if ($c['filter'] !== '') {
        sourceCheckSql($c['filter'], false);
    }
    if ($c['query'] !== '') {
        sourceCheckSql($c['query'], true);
    }

    $map = isset($in['map']) && is_array($in['map']) ? $in['map'] : [];
    foreach (catalogFieldLabels() as $field => $label) {
        $c['map'][$field] = sourceText($map, $field, 200, 'The ' . $label . ' column');
    }
    $c['price_unit'] = ($in['price_unit'] ?? '') === 'cent' ? 'cent' : 'peso';
    $c['add_vat'] = !empty($in['add_vat']);
    $c['encoding'] = in_array($in['encoding'] ?? '', ['auto', 'utf-8', 'windows-1252'], true) ? $in['encoding'] : 'auto';

    $schedule = (string) ($in['schedule'] ?? '15');
    $c['schedule'] = isset(sourceSchedules()[$schedule]) ? $schedule : '15';
    $dailyAt = trim((string) ($in['daily_at'] ?? '06:00'));
    if (!preg_match('/^([01]?\d|2[0-3]):[0-5]\d$/', $dailyAt)) {
        throw new RuntimeException('Type the daily sync time as HH:MM, like 06:00.');
    }
    $c['daily_at'] = sprintf('%05s', $dailyAt);
    $c['mode'] = ($in['mode'] ?? '') === 'merge' ? 'merge' : 'replace';
    $c['notify'] = !empty($in['notify']);
    $drop = trim((string) ($in['max_drop'] ?? '50'));
    if (!ctype_digit($drop) || (int) $drop > 100) {
        throw new RuntimeException('The safety stop must be a number from 0 to 100.');
    }
    $c['max_drop'] = (int) $drop;
    return $c;
}

/**
 * Throws when a setting the next step needs is missing. $stage: 'connect'
 * (reach the source), 'read' (also what to read) or 'map' (also the columns).
 */
function sourceRequire(array $c, string $stage): void
{
    $kind = $c['kind'];
    $label = sourceKinds()[$kind]['label'];
    [$ready, $why] = sourceKindReady($kind);
    if (!$ready) {
        throw new RuntimeException($label . ': ' . $why);
    }
    $need = static function (bool $ok, string $message): void {
        if (!$ok) {
            throw new RuntimeException($message);
        }
    };
    if (in_array($kind, ['mysql', 'sqlserver', 'postgres', 'hana', 'oracle', 'db2'], true)) {
        $need($c['host'] !== '', 'Type the address of the database server.');
    }
    if (in_array($kind, ['mysql', 'postgres', 'oracle', 'db2', 'firebird'], true)) {
        $need($c['database'] !== '', $kind === 'oracle' ? 'Type the Oracle service name.'
            : ($kind === 'firebird' ? 'Type the database file path or alias.' : 'Type the database name.'));
    }
    if (in_array($kind, ['mysql', 'postgres', 'hana', 'oracle', 'db2', 'firebird'], true)
        || ($kind === 'sqlserver' && !$c['windows_auth'])) {
        $need($c['username'] !== '', 'Type the database user name.');
    }
    if (in_array($kind, ['access', 'sqlite', 'file'], true)) {
        $need($c['file'] !== '', 'Type the path of the file on this server.');
    }
    $need($kind !== 'odbc' || $c['dsn'] !== '', 'Type the ODBC data source name (DSN) or a connection string.');
    $need($kind !== 'url' || $c['url'] !== '', 'Type the web address of the item list.');
    if ($stage === 'connect') {
        return;
    }
    if (sourceIsDatabase($kind)) {
        $need($c['read'] !== 'table' || $c['table'] !== '', 'Type or choose the table (or view) that holds the products.');
        $need($c['read'] !== 'query' || $c['query'] !== '', 'Type the query that returns the products.');
    }
    if ($stage === 'map') {
        foreach (['barcode', 'name', 'price'] as $field) {
            $need($c['map'][$field] !== '', 'Choose the column that holds the ' . strtolower(catalogFieldLabels()[$field]) . '.');
        }
    }
}

/** Whether the saved settings are complete enough to sync. */
function sourceConfigured(array $c): bool
{
    try {
        sourceRequire($c, 'map');
        return true;
    } catch (Throwable $error) {
        return false;
    }
}

/**
 * Accepts only a single SELECT (or, for a filter, a condition): nothing that
 * could change the POS database or run a procedure. A database user that can
 * only read is still the real protection; this catches mistakes.
 */
function sourceCheckSql(string $sql, bool $wholeQuery): void
{
    if (strpos($sql, '/*!') !== false) {
        throw new RuntimeException('The query cannot use /*! ... */ comments.');
    }
    // Text in quotes and comments cannot run anything, so leave them out of the checks.
    $code = (string) preg_replace("/'(?:[^']|'')*'/", "''", $sql);
    $code = (string) preg_replace('/"(?:[^"]|"")*"|\[[^\]]*\]|`[^`]*`/', 'x', $code);
    $code = (string) preg_replace('/--[^\n]*|\/\*.*?\*\//s', ' ', $code);
    $code = rtrim(trim($code), "; \t\n");
    if (strpos($code, ';') !== false) {
        throw new RuntimeException($wholeQuery ? 'Use a single SELECT query (no ; between statements).' : 'The filter cannot contain ;.');
    }
    if ($wholeQuery && !preg_match('/^\(*\s*(select|with)\b/i', $code)) {
        throw new RuntimeException('The query must start with SELECT (or WITH).');
    }
    if (preg_match('/\b(insert|update|delete|merge|upsert|drop|alter|create|truncate|grant|revoke|exec|execute|call|into|openrowset|opendatasource|openquery|shutdown)\b/i', $code, $word)) {
        throw new RuntimeException('The ' . ($wholeQuery ? 'query' : 'filter') . ' can only read: it cannot use ' . strtoupper($word[1]) . '.');
    }
}

/** A table or column name written for the database, unless the user already quoted it. */
function sourceQuoteName(string $kind, string $name): string
{
    $name = trim($name);
    if ($name === '' || preg_match('/["`\[\]]/', $name)) {
        return $name;
    }
    $quote = static function (string $part) use ($kind): string {
        switch ($kind) {
            case 'mysql':
                return '`' . str_replace('`', '``', $part) . '`';
            case 'sqlserver':
            case 'access':
                return '[' . str_replace(']', ']]', $part) . ']';
            case 'sqlite':
                return '"' . str_replace('"', '""', $part) . '"';
        }
        // The other databases fold plain names to one case: quote only names
        // that would not survive that (mixed case, spaces and the like).
        $fold = $kind === 'postgres' ? strtolower($part) : strtoupper($part);
        $plain = preg_match('/^[A-Za-z_][A-Za-z0-9_$]*$/', $part) && ($kind === 'odbc' || $part === $fold);
        return $plain ? $part : '"' . str_replace('"', '""', $part) . '"';
    };
    return implode('.', array_map($quote, explode('.', $name)));
}

/** The SQL that reads the source: $limit rows at most (0 = all), only the mapped columns when $mappedOnly. */
function sourceSql(array $c, int $limit, bool $mappedOnly): string
{
    $kind = $c['kind'];
    if ($c['read'] === 'query') {
        $sql = rtrim(trim($c['query']), "; \t\n");
        if ($limit <= 0) {
            return $sql;
        }
        switch ($kind) {
            case 'sqlserver':
            case 'access':
                return 'SELECT TOP ' . $limit . ' * FROM (' . $sql . ') AS pv_rows';
            case 'oracle':
                return 'SELECT * FROM (' . $sql . ') WHERE ROWNUM <= ' . $limit;
            case 'firebird':
                return 'SELECT FIRST ' . $limit . ' * FROM (' . $sql . ') pv_rows';
            case 'db2':
                return 'SELECT * FROM (' . $sql . ') AS pv_rows FETCH FIRST ' . $limit . ' ROWS ONLY';
            case 'odbc':
                return $sql;
        }
        return 'SELECT * FROM (' . $sql . ') AS pv_rows LIMIT ' . $limit;
    }
    $columns = '*';
    if ($mappedOnly) {
        $names = array_values(array_unique(array_filter($c['map'], 'strlen')));
        $columns = implode(', ', array_map(static function (string $name) use ($kind): string {
            return sourceQuoteName($kind, $name);
        }, $names));
    }
    $from = ' FROM ' . sourceQuoteName($kind, $c['table']);
    $where = $c['filter'] !== '' ? '(' . $c['filter'] . ')' : '';
    if ($limit <= 0 || $kind === 'odbc') {
        return 'SELECT ' . $columns . $from . ($where !== '' ? ' WHERE ' . $where : '');
    }
    switch ($kind) {
        case 'sqlserver':
        case 'access':
            return 'SELECT TOP ' . $limit . ' ' . $columns . $from . ($where !== '' ? ' WHERE ' . $where : '');
        case 'oracle':
            return 'SELECT ' . $columns . $from . ' WHERE ' . ($where !== '' ? $where . ' AND ' : '') . 'ROWNUM <= ' . $limit;
        case 'firebird':
            return 'SELECT FIRST ' . $limit . ' ' . $columns . $from . ($where !== '' ? ' WHERE ' . $where : '');
        case 'db2':
            return 'SELECT ' . $columns . $from . ($where !== '' ? ' WHERE ' . $where : '') . ' FETCH FIRST ' . $limit . ' ROWS ONLY';
    }
    return 'SELECT ' . $columns . $from . ($where !== '' ? ' WHERE ' . $where : '') . ' LIMIT ' . $limit;
}

/** A value for an ODBC connection string, in braces when it has characters that need them. */
function sourceOdbcValue(string $value): string
{
    return preg_match('/[;{}=\s]|^$/', $value) ? '{' . str_replace('}', '}}', $value) . '}' : $value;
}

/** A PDO error in words a store owner can act on, with the database's own message after it. */
function sourceConnectError(Throwable $error, array $c): RuntimeException
{
    $message = trim((string) preg_replace('/\s+/', ' ', $error->getMessage()));
    $state = preg_match('/SQLSTATE\[(\w+)\]/', $message, $code) ? $code[1] : '';
    $where = $c['host'] !== '' ? $c['host'] . ($c['instance'] !== '' ? '\\' . $c['instance'] : '') : 'the database';
    if ($state === '28000' || preg_match('/access denied|login failed|password authentication failed|invalid user|authentication fail/i', $message)) {
        $hint = 'The database did not accept the user name or password.';
    } elseif (preg_match('/IM002|data source name not found/i', $message)) {
        $hint = 'The ODBC driver or data source name was not found on this server.';
    } elseif (preg_match('/^08|HYT00/', $state) || preg_match('/could not (connect|translate)|timed? ?out|refused|does not exist or access denied|could not open a connection|connection attempt failed|network-related|unable to connect|no route|unknown (mysql )?server host|getaddrinfo|name or service not known/i', $message)) {
        $hint = 'Could not reach ' . $where . '. Check the address and port, that the database server is running and accepts'
            . ' network connections, and that its firewall allows this server.';
    } elseif (preg_match('/unknown database|cannot open database|database .* does not exist|ORA-12514/i', $message)) {
        $hint = 'The server was reached but the database name was not found.';
    } else {
        $hint = 'The database reported an error.';
    }
    $detail = function_exists('mb_strimwidth') ? mb_strimwidth($message, 0, 400, '...') : substr($message, 0, 400);
    return new RuntimeException($hint . ' Details: ' . $detail);
}

/** Connects over ODBC, trying each candidate driver that is not installed in turn. Returns [PDO, driver]. */
function sourceOdbcConnect(array $c, array $pairs, array $options): array
{
    $drivers = $c['driver'] !== '' ? [$c['driver']] : sourceDriverCandidates($c['kind']);
    if (!$drivers) {
        throw new RuntimeException('Choose the ODBC driver to use.' . (sourceInstalledDrivers()
            ? ' Installed drivers: ' . implode(', ', sourceInstalledDrivers()) . '.' : ''));
    }
    $text = '';
    foreach ($pairs as $key => $value) {
        $text .= ';' . $key . '=' . sourceOdbcValue((string) $value);
    }
    $missing = [];
    foreach ($drivers as $driver) {
        try {
            return [new PDO('odbc:Driver={' . $driver . '}' . $text, null, null, $options), $driver];
        } catch (PDOException $error) {
            if (preg_match('/IM002|data source name not found|specified driver could not be loaded/i', $error->getMessage())) {
                $missing[] = $driver;
                continue;
            }
            throw sourceConnectError($error, $c);
        }
    }
    throw new RuntimeException('The ODBC driver ' . implode(' / ', $missing) . ' is not installed on this server. '
        . sourceKinds()[$c['kind']]['needs']
        . (sourceInstalledDrivers() ? ' Installed drivers: ' . implode(', ', sourceInstalledDrivers()) . '.' : ''));
}

/** Opens the source database, read-only wherever the database allows it. Returns [PDO, how it connected]. */
function sourceConnect(array $c): array
{
    sourceRequire($c, 'connect');
    $options = [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_TIMEOUT => 15];
    $drivers = PDO::getAvailableDrivers();
    $port = $c['port'] !== '' ? $c['port'] : (string) sourceKinds()[$c['kind']]['port'];
    $user = $c['username'] !== '' ? $c['username'] : null;
    $login = $c['username'] !== '' ? ['UID' => $c['username'], 'PWD' => $c['password']] : [];
    $after = [];
    try {
        switch ($c['kind']) {
            case 'mysql':
                if (defined('PDO::MYSQL_ATTR_USE_BUFFERED_QUERY')) {
                    $options[PDO::MYSQL_ATTR_USE_BUFFERED_QUERY] = false;   // stream large tables
                }
                $pdo = new PDO('mysql:host=' . $c['host'] . ';port=' . $port . ';dbname=' . $c['database'] . ';charset=utf8mb4',
                    $user, $c['password'], $options);
                $how = 'MySQL';
                $after = ['SET SESSION TRANSACTION READ ONLY'];
                break;
            case 'postgres':
                $pdo = new PDO('pgsql:host=' . $c['host'] . ';port=' . $port . ';dbname=' . $c['database'] . ';connect_timeout=15',
                    $user, $c['password'], $options);
                $how = 'PostgreSQL';
                $after = ['SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY', 'SET statement_timeout = 600000'];
                break;
            case 'sqlite':
                $path = sourceLocalFile($c['file'], ['db', 'sqlite', 'sqlite3', 'db3', 's3db']);
                if (defined('PDO::SQLITE_ATTR_OPEN_FLAGS')) {
                    $options[PDO::SQLITE_ATTR_OPEN_FLAGS] = PDO::SQLITE_OPEN_READONLY;
                }
                $pdo = new PDO('sqlite:' . $path, null, null, $options);
                $how = 'SQLite';
                break;
            case 'sqlserver':
                $server = $c['host'] . ($c['instance'] !== '' ? '\\' . $c['instance'] : '') . ($c['port'] !== '' ? ',' . $c['port'] : '');
                if (in_array('sqlsrv', $drivers, true)) {
                    $pdo = new PDO('sqlsrv:Server=' . $server . ';Database=' . $c['database'] . ';LoginTimeout=15;Encrypt='
                        . ($c['encrypt'] ? '1' : '0') . ';TrustServerCertificate=1',
                        $c['windows_auth'] ? null : $user, $c['windows_auth'] ? null : $c['password'], $options);
                    $how = 'SQL Server (sqlsrv)';
                } elseif (!in_array('odbc', $drivers, true)) {
                    $pdo = new PDO('dblib:host=' . $c['host'] . ':' . $port . ';dbname=' . $c['database'] . ';charset=UTF-8',
                        $user, $c['password'], $options);
                    $how = 'SQL Server (FreeTDS)';
                } else {
                    $pairs = ['Server' => $server];
                    if ($c['database'] !== '') {
                        $pairs['Database'] = $c['database'];
                    }
                    $pairs += $c['windows_auth'] ? ['Trusted_Connection' => 'yes'] : $login;
                    $pairs += ['Encrypt' => $c['encrypt'] ? 'yes' : 'no', 'TrustServerCertificate' => 'yes', 'APP' => 'Price Verifier'];
                    [$pdo, $driver] = sourceOdbcConnect($c, $pairs, $options);
                    $how = 'SQL Server via ' . $driver;
                }
                break;
            case 'hana':
                $pairs = ['ServerNode' => $c['host'] . ':' . $port];
                if ($c['database'] !== '') {
                    $pairs['DatabaseName'] = $c['database'];
                }
                $pairs += $login;
                if ($c['encrypt']) {
                    $pairs += ['encrypt' => 'TRUE', 'sslValidateCertificate' => 'FALSE'];
                }
                [$pdo, $driver] = sourceOdbcConnect($c, $pairs, $options);
                $how = 'SAP HANA via ' . $driver;
                $after = ['SET TRANSACTION READ ONLY'];
                break;
            case 'oracle':
                if (in_array('oci', $drivers, true) && $c['driver'] === '') {
                    $pdo = new PDO('oci:dbname=//' . $c['host'] . ':' . $port . '/' . $c['database'] . ';charset=AL32UTF8',
                        $user, $c['password'], $options);
                    $how = 'Oracle (OCI)';
                } else {
                    [$pdo, $driver] = sourceOdbcConnect($c, ['DBQ' => $c['host'] . ':' . $port . '/' . $c['database']] + $login, $options);
                    $how = 'Oracle via ' . $driver;
                }
                break;
            case 'firebird':
                $database = ($c['host'] !== '' ? $c['host'] . '/' . $port . ':' : '') . $c['database'];
                if (in_array('firebird', $drivers, true) && $c['driver'] === '') {
                    $pdo = new PDO('firebird:dbname=' . $database . ';charset=UTF8', $user, $c['password'], $options);
                    $how = 'Firebird';
                } else {
                    [$pdo, $driver] = sourceOdbcConnect($c, ['DBNAME' => $database] + $login + ['CHARSET' => 'UTF8', 'READONLY' => 'Yes'], $options);
                    $how = 'Firebird via ' . $driver;
                }
                break;
            case 'db2':
                [$pdo, $driver] = sourceOdbcConnect($c, ['Database' => $c['database'], 'Hostname' => $c['host'], 'Port' => $port,
                    'Protocol' => 'TCPIP'] + $login, $options);
                $how = 'Db2 via ' . $driver;
                break;
            case 'access':
                $path = sourceLocalFile($c['file'], ['mdb', 'accdb']);
                $pairs = ['Dbq' => $path, 'ReadOnly' => '1', 'Exclusive' => '0'];
                if ($c['password'] !== '') {
                    $pairs['PWD'] = $c['password'];
                }
                [$pdo, $driver] = sourceOdbcConnect($c, $pairs, $options);
                $how = 'Access via ' . $driver;
                break;
            default:   // odbc: a DSN name or a whole connection string
                $text = strpos($c['dsn'], '=') !== false ? rtrim($c['dsn'], '; ') : 'DSN=' . sourceOdbcValue($c['dsn']);
                if ($login && !preg_match('/(^|;)\s*(UID|User ?ID)\s*=/i', $text)) {
                    $text .= ';UID=' . sourceOdbcValue($c['username']) . ';PWD=' . sourceOdbcValue($c['password']);
                }
                $pdo = new PDO('odbc:' . $text, null, null, $options);
                $how = 'ODBC';
        }
    } catch (PDOException $error) {
        throw sourceConnectError($error, $c);
    }
    foreach ($after as $statement) {
        try {
            $pdo->exec($statement);
        } catch (Throwable $ignored) {
            // An older server without read-only sessions: the read-only user still protects it.
        }
    }
    return [$pdo, $how];
}

/**
 * A file on this server named in the settings: the newest match when the name
 * has * or ?, with one of the $extensions, and never one of the portal's own files.
 */
function sourceLocalFile(string $pattern, array $extensions): string
{
    $path = $pattern;
    if (strpbrk(basename(str_replace('\\', '/', $pattern)), '*?') !== false) {
        $newest = 0;
        $path = '';
        foreach (glob($pattern) ?: [] as $match) {
            if (is_file($match) && in_array(strtolower(pathinfo($match, PATHINFO_EXTENSION)), $extensions, true)
                && filemtime($match) >= $newest) {
                $newest = (int) filemtime($match);
                $path = $match;
            }
        }
        if ($path === '') {
            throw new RuntimeException('No file matches ' . $pattern . ' on this server.');
        }
    }
    if (!in_array(strtolower(pathinfo($path, PATHINFO_EXTENSION)), $extensions, true)) {
        throw new RuntimeException('Choose a file that ends in .' . implode(', .', $extensions) . '.');
    }
    if (!is_file($path) || !is_readable($path)) {
        throw new RuntimeException('The file ' . $path . ' was not found, or this server is not allowed to read it.'
            . (preg_match('#^(\\\\\\\\|//)#', $path) ? ' The server runs as the system account, so the share must allow it'
                . ' (or copy the file to a folder on this PC).' : ''));
    }
    $real = realpath($path);
    if ($real === false || strpos(strtolower($real), strtolower(realpath(__DIR__) . DIRECTORY_SEPARATOR)) === 0) {
        throw new RuntimeException('Choose a file outside the portal folder.');
    }
    return $real;
}

/**
 * A database value as one line of UTF-8 text. "auto" keeps valid UTF-8 and
 * reads anything else as Windows-1252, which is what older Windows POS
 * databases store.
 */
function sourceCell($value, string $encoding): string
{
    if ($value === null) {
        return '';
    }
    if (is_resource($value)) {
        $value = (string) stream_get_contents($value);
    }
    if (is_bool($value)) {
        return $value ? '1' : '0';
    }
    if (is_float($value)) {
        $value = floor($value) == $value && abs($value) < 1e15
            ? sprintf('%.0F', $value) : rtrim(rtrim(sprintf('%.6F', $value), '0'), '.');
    }
    $text = (string) $value;
    if ($text === '') {
        return '';
    }
    $valid = preg_match('//u', $text) === 1;
    if ($encoding === 'windows-1252' || ($encoding === 'auto' && !$valid)) {
        $text = function_exists('mb_convert_encoding')
            ? (string) mb_convert_encoding($text, 'UTF-8', 'Windows-1252')
            : (string) @iconv('Windows-1252', 'UTF-8//IGNORE', $text);
    } elseif (!$valid && function_exists('mb_scrub')) {
        $text = mb_scrub($text, 'UTF-8');
    }
    return trim((string) preg_replace('/[\x00-\x1F\x7F]+/', ' ', $text));
}

/** A barcode from any column type: no spaces, and no ".0" or exponent from a number column. */
function sourceBarcode(string $value): string
{
    $barcode = (string) preg_replace('/\s+/', '', ltrim($value, "'"));
    if (preg_match('/^(\d+)\.0*$/', $barcode, $whole)) {
        $barcode = $whole[1];
    } elseif (isScientificBarcode($barcode) && (float) $barcode < 1e15 && floor((float) $barcode) == (float) $barcode) {
        $barcode = sprintf('%.0F', (float) $barcode);
    }
    return $barcode;
}

/** The selling price as 0.00 after the unit and VAT settings, or null when it is not a usable price. */
function sourcePrice(string $value, array $c): ?string
{
    $value = str_replace(' ', '', trim($value));
    // "10,50" (one comma, one or two digits after it) is a decimal comma; "1,250" is a thousands separator.
    $value = preg_match('/^[^,.]*\d,\d{1,2}$/', $value) ? str_replace(',', '.', $value) : str_replace(',', '', $value);
    $clean = (string) preg_replace('/^(?:php|p|\$|\x{20B1})\s*/iu', '', $value);
    if (!is_numeric($clean)) {
        return null;
    }
    if ($c['price_unit'] === 'peso' && !$c['add_vat']) {
        $price = catalogPriceFromCell($clean);   // decimal rounding, no floating point drift
        return is_numeric($price) && (float) $price >= 0 ? $price : null;
    }
    $amount = (float) $clean;
    if ($c['price_unit'] === 'cent') {
        $amount /= 100;
    }
    if ($c['add_vat']) {
        $amount *= 1.12;
    }
    return is_finite($amount) && $amount >= 0 ? number_format(round($amount, 2), 2, '.', '') : null;
}

/** The value of a mapped column in a row, matching the column name exactly or else ignoring case. */
function sourceValue(array $row, string $column): ?string
{
    if ($column === '') {
        return '';
    }
    if (array_key_exists($column, $row)) {
        return (string) $row[$column];
    }
    foreach ($row as $key => $value) {
        if (strcasecmp((string) $key, $column) === 0) {
            return (string) $value;
        }
    }
    return null;
}

/**
 * A catalog line [barcode, name, uom, category, price, item_code] from a
 * source row, or [null, reason] with reason 'barcode', 'name' or 'price'.
 */
function sourceItem(array $row, array $c): array
{
    $values = [];
    foreach ($c['map'] as $field => $column) {
        // A field missing from one row (JSON items often leave out empty ones) is blank;
        // a column missing from every row is reported by sourceCheckColumns().
        $values[$field] = (string) sourceValue($row, $column);
    }
    $barcode = sourceBarcode($values['barcode']);
    if ($barcode === '') {
        return [null, 'barcode'];
    }
    if ($values['name'] === '') {
        return [null, 'name'];
    }
    $price = sourcePrice($values['price'], $c);
    if ($price === null) {
        return [null, 'price'];
    }
    return [[$barcode, $values['name'], $values['uom'], $values['category'], $price, $values['item_code']], ''];
}

/** Throws when a mapped column is not among the columns the source returned. */
function sourceCheckColumns(array $c, array $columns): void
{
    $known = array_map('strtolower', $columns);
    foreach ($c['map'] as $field => $column) {
        if ($column !== '' && !in_array(strtolower($column), $known, true)) {
            throw new RuntimeException('The column "' . $column . '" (' . catalogFieldLabels()[$field] . ') is not in what the source returns.'
                . ($columns ? ' It has: ' . implode(', ', array_slice($columns, 0, 40)) . (count($columns) > 40 ? ', ...' : '') . '.' : ''));
        }
    }
}

/**
 * Reads the source, calling $onRow(array $row) with column name => text for
 * each row, $limit rows at most (0 = all). Returns the column names.
 */
function sourceRead(array $c, int $limit, callable $onRow, bool $mappedOnly = false): array
{
    if ($c['kind'] === 'file') {
        return sourceReadTable(sourceLocalFile($c['file'], ['csv', 'txt', 'xlsx', 'xls']), $c, $limit, $onRow);
    }
    if ($c['kind'] === 'url') {
        return sourceReadUrl($c, $limit, $onRow);
    }
    [$pdo] = sourceConnect($c);
    $sql = sourceSql($c, $limit, $mappedOnly);
    try {
        $statement = $pdo->query($sql);
    } catch (PDOException $error) {
        $plain = sourceSql($c, 0, $mappedOnly);
        if ($plain === $sql) {
            throw new RuntimeException('The database could not run the query: ' . trim($error->getMessage()));
        }
        // A row limit this database does not accept: read without it and stop early.
        try {
            $statement = $pdo->query($plain);
        } catch (PDOException $again) {
            throw new RuntimeException('The database could not run the query: ' . trim($again->getMessage()));
        }
    }
    $columns = [];
    $count = 0;
    while (($row = $statement->fetch(PDO::FETCH_ASSOC)) !== false) {
        if (!$columns) {
            $columns = array_map('strval', array_keys($row));
        }
        foreach ($row as $key => $value) {
            $row[$key] = sourceCell($value, $c['encoding']);
        }
        $onRow($row);
        if ($limit > 0 && ++$count >= $limit) {
            break;
        }
    }
    if (!$columns) {
        for ($index = 0; $index < $statement->columnCount(); $index++) {
            try {
                $meta = $statement->getColumnMeta($index);
            } catch (Throwable $error) {
                $meta = false;
            }
            $columns[] = is_array($meta) && isset($meta['name']) ? (string) $meta['name'] : 'Column ' . ($index + 1);
        }
    }
    $statement = null;
    $pdo = null;
    return $columns;
}

/** Header names made usable as keys: blanks become "Column N", repeats get " (2)". */
function sourceHeaderNames(array $cells): array
{
    $names = [];
    foreach (array_values($cells) as $index => $cell) {
        $name = trim((string) $cell, "\xEF\xBB\xBF \t");
        $name = $name === '' ? 'Column ' . ($index + 1) : $name;
        $unique = $name;
        for ($n = 2; in_array($unique, $names, true); $n++) {
            $unique = $name . ' (' . $n . ')';
        }
        $names[] = $unique;
    }
    return $names;
}

/**
 * Reads a CSV/TXT (comma, semicolon, tab or | separated) or Excel file whose
 * header row is within its first 10 rows. Returns the column names.
 */
function sourceReadTable(string $path, array $c, int $limit, callable $onRow): array
{
    $header = null;
    $scanned = 0;
    $count = 0;
    $each = static function (array $cells) use (&$header, &$scanned, &$count, $limit, $c, $onRow): bool {
        if ($header === null) {
            // The first row with two or more filled cells is the header.
            if (++$scanned <= 10 && count(array_filter($cells, static function ($cell): bool { return trim((string) $cell) !== ''; })) >= 2) {
                $header = sourceHeaderNames($cells);
            }
            return true;
        }
        $row = [];
        foreach ($header as $index => $name) {
            $row[$name] = sourceCell((string) ($cells[$index] ?? ''), $c['encoding']);
        }
        if (implode('', $row) === '') {
            return true;
        }
        $onRow($row);
        return $limit <= 0 || ++$count < $limit;
    };
    $extension = strtolower(pathinfo($path, PATHINFO_EXTENSION));
    if (in_array($extension, ['xlsx', 'xls'], true)) {
        $reader = excelWorkbookKind($path) === 'xls' ? xlsRowReader($path) : xlsxRowReader($path);
        try {
            $reader(static function (array $cells) use ($each): void {
                if (!$each($cells)) {
                    throw new LengthException('enough rows');
                }
            });
        } catch (LengthException $enough) {
            // stopped early on purpose
        }
    } else {
        $handle = fopen($path, 'rb');
        if ($handle === false) {
            throw new RuntimeException('The file ' . $path . ' could not be opened.');
        }
        try {
            $sample = (string) fread($handle, 65536);
            rewind($handle);
            $firstLine = strtok($sample, "\n") ?: '';
            $delimiter = ',';
            $best = 0;
            foreach ([',', ';', "\t", '|'] as $candidate) {
                if (substr_count($firstLine, $candidate) > $best) {
                    $best = substr_count($firstLine, $candidate);
                    $delimiter = $candidate;
                }
            }
            while (($cells = fgetcsv($handle, 0, $delimiter, '"', '')) !== false) {
                if ($cells === [null]) {
                    continue;
                }
                if (!$each($cells)) {
                    break;
                }
            }
        } finally {
            fclose($handle);
        }
    }
    if ($header === null) {
        throw new RuntimeException('No header row was found in ' . basename($path) . ': the first 10 rows need column names.');
    }
    return $header;
}

/** Downloads the web address to a temporary file (100 MB at most). Returns its path. */
function sourceDownload(array $c): string
{
    $headers = ['User-Agent: PriceVerifier', 'Accept: ' . ($c['format'] === 'json' ? 'application/json' : 'text/csv') . ', */*'];
    if ($c['header'] !== '') {
        $headers[] = $c['header'];
    }
    $context = stream_context_create(['http' => ['method' => 'GET', 'header' => implode("\r\n", $headers),
        'timeout' => 60, 'ignore_errors' => true, 'follow_location' => 1, 'max_redirects' => 3]]);
    $in = @fopen($c['url'], 'rb', false, $context);
    if ($in === false) {
        $error = error_get_last();
        throw new RuntimeException('Could not open the web address: ' . trim((string) preg_replace('/^.*?: /', '', (string) ($error['message'] ?? 'no answer'))));
    }
    $staged = __DIR__ . '/.catalog-download-' . bin2hex(random_bytes(6)) . '.tmp';
    try {
        $status = 0;
        foreach ((array) (stream_get_meta_data($in)['wrapper_data'] ?? []) as $line) {
            if (preg_match('#^HTTP/\S+\s+(\d{3})#', (string) $line, $match)) {
                $status = (int) $match[1];   // the last one, after any redirects
            }
        }
        $out = fopen($staged, 'wb');
        if ($out === false) {
            throw new RuntimeException('The server could not save the download. Check the folder permissions.');
        }
        $copied = @stream_copy_to_stream($in, $out, 100 * 1024 * 1024 + 1);
        fclose($out);
        if ($copied === false || $copied > 100 * 1024 * 1024) {
            throw new RuntimeException($copied === false ? 'The download failed.' : 'The download is bigger than 100 MB.');
        }
        if ($status < 200 || $status >= 300) {
            $body = trim((string) preg_replace('/\s+/', ' ', strip_tags((string) file_get_contents($staged, false, null, 0, 300))));
            throw new RuntimeException('The web address answered HTTP ' . $status . ($body !== '' ? ': ' . $body : '.'));
        }
        return $staged;
    } catch (Throwable $error) {
        @unlink($staged);
        throw $error;
    } finally {
        fclose($in);
    }
}

/** JSON flattened to "a.b.0.c" => scalar, a few levels deep. */
function sourceFlatten(array $data, string $prefix = '', int $depth = 0): array
{
    $flat = [];
    foreach ($data as $key => $value) {
        $name = $prefix . $key;
        if (is_array($value)) {
            if ($depth < 3) {
                $flat += sourceFlatten(array_slice($value, 0, 20, true), $name . '.', $depth + 1);
            }
        } else {
            $flat[$name] = is_bool($value) ? ($value ? '1' : '0') : $value;
        }
    }
    return $flat;
}

function sourceReadUrl(array $c, int $limit, callable $onRow): array
{
    $path = sourceDownload($c);
    try {
        if ($c['format'] === 'csv') {
            return sourceReadTable($path, $c, $limit, $onRow);
        }
        if (filesize($path) > 50 * 1024 * 1024) {
            throw new RuntimeException('The JSON is bigger than 50 MB. Ask for a CSV export instead.');
        }
        $data = json_decode((string) file_get_contents($path), true);
        if (!is_array($data)) {
            throw new RuntimeException('The web address did not return valid JSON.');
        }
        // The list of items: at the path given, or the top level, or the first list in the top-level object.
        $records = $data;
        if ($c['records'] !== '') {
            foreach (explode('.', $c['records']) as $step) {
                if (!is_array($records) || !array_key_exists($step, $records)) {
                    throw new RuntimeException('"' . $c['records'] . '" was not found in the JSON.');
                }
                $records = $records[$step];
            }
        } elseif (!array_is_list($records)) {
            foreach ($records as $value) {
                if (is_array($value) && array_is_list($value)) {
                    $records = $value;
                    break;
                }
            }
        }
        if (!is_array($records) || !array_is_list($records)) {
            throw new RuntimeException('No list of items was found in the JSON. Type where the items are, like data.items.');
        }
        $columns = [];
        $count = 0;
        foreach ($records as $record) {
            if (!is_array($record)) {
                continue;
            }
            $row = [];
            foreach (sourceFlatten($record) as $key => $value) {
                $row[(string) $key] = sourceCell($value, $c['encoding']);
            }
            $columns += array_fill_keys(array_keys($row), true);
            $onRow($row);
            if ($limit > 0 && ++$count >= $limit) {
                break;
            }
        }
        return array_map('strval', array_keys($columns));
    } finally {
        @unlink($path);
    }
}

/** Tables and views the source user can read, for the table picker; [] when the database will not say. */
function sourceTables(PDO $pdo, string $kind): array
{
    $sql = [
        'mysql' => 'SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY 1',
        'sqlserver' => "SELECT TABLE_SCHEMA + '.' + TABLE_NAME FROM INFORMATION_SCHEMA.TABLES ORDER BY 1",
        'postgres' => "SELECT table_schema || '.' || table_name FROM information_schema.tables"
            . " WHERE table_schema NOT IN ('pg_catalog', 'information_schema') ORDER BY 1",
        'hana' => "SELECT SCHEMA_NAME || '.' || TABLE_NAME FROM SYS.TABLES WHERE LEFT(SCHEMA_NAME, 4) <> '_SYS' AND SCHEMA_NAME NOT IN ('SYS', 'SYSTEM')"
            . " UNION ALL SELECT SCHEMA_NAME || '.' || VIEW_NAME FROM SYS.VIEWS WHERE LEFT(SCHEMA_NAME, 4) <> '_SYS' AND SCHEMA_NAME NOT IN ('SYS', 'SYSTEM') ORDER BY 1",
        'oracle' => 'SELECT table_name FROM user_tables UNION ALL SELECT view_name FROM user_views ORDER BY 1',
        'firebird' => 'SELECT TRIM(RDB$RELATION_NAME) FROM RDB$RELATIONS WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 ORDER BY 1',
        'db2' => "SELECT TRIM(TABSCHEMA) || '.' || TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA NOT LIKE 'SYS%' ORDER BY 1",
        'sqlite' => "SELECT name FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY 1",
        'access' => "SELECT Name FROM MSysObjects WHERE Type IN (1, 4, 6) AND Flags = 0 AND Name NOT LIKE 'MSys*' ORDER BY Name",
    ][$kind] ?? '';
    if ($sql === '') {
        return [];
    }
    try {
        $tables = [];
        $statement = $pdo->query($sql);
        while (count($tables) < 3000 && ($name = $statement->fetchColumn()) !== false) {
            $tables[] = trim((string) $name);
        }
        return $tables;
    } catch (Throwable $error) {
        return [];
    }
}

/** Which source column most likely holds each catalog field; fields already chosen are kept. */
function sourceGuessMap(array $columns, array $current): array
{
    $hints = [
        'barcode' => ['barcode', 'barcodes', 'bar code', 'upc', 'ean', 'ean13', 'gtin', 'codebars', 'bcdcode', 'plu', 'scancode',
            'in code', 'item barcode', 'product barcode'],
        'name' => ['name', 'item name', 'description', 'descr', 'desc', 'item desc', 'ite desc', 'item description', 'product name',
            'product', 'long description', 'short description', 'title'],
        'price' => ['price', 'retail price', 'selling price', 'srp', 'unit price', 'unt pric', 'sales price', 'sell price', 'price1',
            'regular price', 'retail', 'amount'],
        'uom' => ['uom', 'unit', 'unit code', 'unt code', 'salunitmsr', 'uom code', 'unit of measure', 'measure'],
        'category' => ['category', 'category name', 'department', 'dept', 'dep code', 'dept code', 'itmsgrpnam', 'item group', 'group', 'class'],
        'item_code' => ['item code', 'sku', 'stock code', 'product code', 'item no', 'item number', 'code', 'id'],
    ];
    foreach (catalogHeaderAliases() as $field => $aliases) {
        $hints[$field] = array_merge($hints[$field], $aliases);
    }
    $key = static function (string $name): string {
        return (string) preg_replace('/[^a-z0-9]/', '', strtolower($name));
    };
    $map = [];
    $used = [];
    foreach ($current as $field => $column) {
        if ($column !== '' && in_array(strtolower($column), array_map('strtolower', $columns), true)) {
            $map[$field] = $column;
            $used[strtolower($column)] = true;
        }
    }
    // Exact names first, then names that contain a telling word.
    $partial = ['barcode' => ['barcode', 'upc', 'ean'], 'name' => ['name', 'desc'], 'price' => ['price', 'pric', 'srp']];
    foreach ([false, true] as $loose) {
        foreach ($hints as $field => $names) {
            if (isset($map[$field]) || ($loose && !isset($partial[$field]))) {
                continue;
            }
            foreach ($loose ? $partial[$field] : array_map($key, $names) as $hint) {
                foreach ($columns as $column) {
                    $name = $key($column);
                    if (!isset($used[strtolower($column)]) && ($loose ? strpos($name, $hint) !== false : $name === $hint)) {
                        $map[$field] = $column;
                        $used[strtolower($column)] = true;
                        continue 3;
                    }
                }
            }
        }
    }
    return $map;
}

/** "SQL Server · POS-PC\SQLEXPRESS · POSDB": where the products come from, for the status line. */
function sourceDescribe(array $c): string
{
    $parts = [sourceKinds()[$c['kind']]['label']];
    if ($c['host'] !== '' && in_array('host', sourceKinds()[$c['kind']]['fields'], true)) {
        $parts[] = $c['host'] . ($c['kind'] === 'sqlserver' && $c['instance'] !== '' ? '\\' . $c['instance'] : '');
    }
    if ($c['database'] !== '' && in_array('database', sourceKinds()[$c['kind']]['fields'], true)) {
        $parts[] = $c['database'];
    }
    if (in_array($c['kind'], ['access', 'sqlite', 'file'], true)) {
        $parts[] = basename(str_replace('\\', '/', $c['file']));
    }
    if ($c['kind'] === 'url') {
        $parts[] = (string) parse_url($c['url'], PHP_URL_HOST);
    }
    if (sourceIsDatabase($c['kind'])) {
        $parts[] = $c['read'] === 'query' ? 'custom query' : $c['table'];
    }
    return implode(' · ', array_filter($parts, 'strlen'));
}

/** When the scheduler should sync next (a timestamp), or null when it never syncs by itself. */
function sourceNextRun(array $c, array $state, int $now): ?int
{
    if (!$c['enabled'] || $c['schedule'] === 'manual' || !sourceConfigured($c)) {
        return null;
    }
    $last = (int) ($state['last_try'] ?? 0);
    if ($c['saved_at'] > $last) {
        return $now;   // just set up or changed
    }
    if ($c['schedule'] === 'daily') {
        $today = (int) strtotime(date('Y-m-d', $now) . ' ' . $c['daily_at']);
        if ((int) ($state['last_ok'] ?? 0) >= $today) {
            return (int) strtotime('+1 day', $today);
        }
        // Today's sync failed: try again every 15 minutes.
        return $last >= $today && empty($state['ok']) ? $last + 900 : $today;
    }
    $every = (int) $c['schedule'] * 60;
    // After a failure, try again within 5 minutes.
    return $last + (empty($state['ok']) && $last > 0 ? min($every, 300) : $every);
}

/** "3 without a barcode, 1 with an invalid price" for a sync's skipped rows. */
function sourceSkippedText(array $stats): string
{
    $parts = [];
    foreach (['barcode' => 'without a barcode', 'name' => 'without a name', 'price' => 'without a valid price',
                 'duplicates' => 'repeating a barcode already read'] as $key => $words) {
        if (!empty($stats[$key])) {
            $parts[] = number_format((int) $stats[$key]) . ' ' . $words;
        }
    }
    return implode(', ', $parts);
}

/**
 * Reads the whole source into a staged catalog file with the standard columns.
 * A barcode that appears again is skipped: the first row read wins.
 */
function sourceExport(array $c): array
{
    $staged = __DIR__ . '/.catalog-' . bin2hex(random_bytes(8)) . '.tmp';
    $out = fopen($staged, 'wb');
    if ($out === false) {
        throw new RuntimeException('The server could not write the new catalog. Check the folder permissions.');
    }
    $stats = ['rows' => 0, 'items' => 0, 'barcode' => 0, 'name' => 0, 'price' => 0, 'duplicates' => 0];
    $seen = [];
    try {
        fputcsv($out, ['barcode', 'name', 'uom', 'category', 'price', 'item_code'], ',', '"', '');
        $columns = sourceRead($c, 0, static function (array $row) use ($c, $out, &$stats, &$seen): void {
            if (++$stats['rows'] > SOURCE_MAX_ROWS) {
                throw new RuntimeException('The source returned more than ' . number_format(SOURCE_MAX_ROWS)
                    . ' rows. Add a filter so it returns only the products to show.');
            }
            [$item, $problem] = sourceItem($row, $c);
            if ($item === null) {
                $stats[$problem]++;
            } elseif (isset($seen[$item[0]])) {
                $stats['duplicates']++;
            } else {
                $seen[$item[0]] = true;
                fputcsv($out, $item, ',', '"', '');
                $stats['items']++;
            }
        }, true);
        sourceCheckColumns($c, $columns);
    } catch (Throwable $error) {
        fclose($out);
        @unlink($staged);
        throw $error;
    }
    fclose($out);
    return ['path' => $staged, 'stats' => $stats];
}

/**
 * Reads the source and, when that gives a usable catalog, makes it the live
 * catalog.csv and tells the scanners. Keeps the current catalog on any
 * problem, when the source has no products, or (unless $force) when the
 * product count drops by more than the safety stop allows.
 * Returns ['ok', 'changed', 'items', 'message'].
 */
function catalogSourceSync(bool $force, string $trigger): array
{
    $lock = @fopen(SOURCE_LOCK_FILE, 'c');
    if ($lock === false || !flock($lock, LOCK_EX | LOCK_NB)) {
        if ($lock !== false) {
            fclose($lock);
        }
        return ['ok' => false, 'changed' => false, 'items' => null, 'busy' => true,
            'message' => 'A sync is already running. Try again in a minute.'];
    }
    $started = microtime(true);
    $c = sourceConfig();
    $state = sourceState();
    $temporary = [];
    $stats = null;
    $fileSignature = null;
    try {
        if (!sourceConfigured($c)) {
            throw new RuntimeException('Finish setting up the data source first.');
        }
        $unchangedFile = false;
        if ($c['kind'] === 'file') {
            $path = sourceLocalFile($c['file'], ['csv', 'txt', 'xlsx', 'xls']);
            $fileSignature = $path . '|' . filemtime($path) . '|' . filesize($path);
            // Nothing to do while the export file is the one already synced.
            $unchangedFile = !$force && !empty($state['ok']) && ($state['file_sig'] ?? '') === $fileSignature
                && (int) ($state['last_ok'] ?? 0) >= (int) $c['saved_at'] && is_file(activeCatalogPath());
        }
        if ($unchangedFile) {
            $result = ['ok' => true, 'changed' => false, 'items' => $state['items'] ?? null,
                'message' => 'No changes: the export file is the same as at the last sync.'];
            $stats = $state['stats'] ?? null;
        } else {
            $export = sourceExport($c);
            $temporary[] = $export['path'];
            $stats = $export['stats'];
            $skipped = sourceSkippedText($stats);
            if ($stats['items'] === 0) {
                throw new RuntimeException('The source returned no usable products'
                    . ($stats['rows'] > 0 ? ' (' . number_format($stats['rows']) . ' rows read: ' . $skipped . ')' : '')
                    . '. The current catalog was kept.');
            }
            $current = activeCatalogPath();
            $source = $export['path'];
            $total = $stats['items'];
            if ($c['mode'] === 'merge' && is_file($current)) {
                $merged = mergeCatalogUpload($source, $current);
                $temporary[] = $merged['path'];
                $source = $merged['path'];
                $total = $merged['total'];
            }
            $previous = is_file($current) ? catalogItemCount($current) : null;
            if (!$force && $c['max_drop'] > 0 && $previous !== null && $previous >= 20
                && $total < $previous * (100 - $c['max_drop']) / 100) {
                throw new RuntimeException('Safety stop: the source gave ' . number_format($total) . ' products but the catalog has '
                    . number_format($previous) . ' (' . (int) round(100 - $total * 100 / $previous) . '% fewer). The catalog was kept.'
                    . ' If this is expected, press Sync now with "Skip the safety stop" ticked.');
            }
            $changed = !is_file($current) || basename($current) !== 'catalog.csv'
                || hash_file('sha256', $current) !== hash_file('sha256', $source);
            if ($changed) {
                @chmod($source, 0644);
                $target = __DIR__ . '/catalog.csv';
                if (!@rename($source, $target)) {
                    @unlink($target);
                    if (!@rename($source, $target)) {
                        throw new RuntimeException('catalog.csv could not be replaced. Is it open in another program?');
                    }
                }
                @unlink(__DIR__ . '/catalog.txt');
                if ($c['notify']) {
                    try {
                        requestDeviceSync('items');
                    } catch (Throwable $ignored) {
                        // Scanners still pick it up on their own schedule.
                    }
                }
            }
            $seconds = number_format(microtime(true) - $started, 1);
            $message = ($changed ? 'Updated the catalog: ' : 'No changes: ') . number_format($total) . ($total === 1 ? ' product' : ' products')
                . ($c['mode'] === 'merge' ? ' after adding ' . number_format($stats['items']) . ' from the source' : '')
                . ' (' . $seconds . ' s).'
                . ($skipped !== '' ? ' Skipped ' . $skipped . '.' : '')
                . ($changed && $c['notify'] ? ' Scanners were told to update.' : '');
            $result = ['ok' => true, 'changed' => $changed, 'items' => $total, 'message' => $message];
        }
    } catch (Throwable $error) {
        $result = ['ok' => false, 'changed' => false, 'items' => null, 'message' => $error->getMessage()];
    } finally {
        foreach ($temporary as $file) {
            if (is_file($file)) {
                @unlink($file);
            }
        }
    }
    $now = time();
    $state['last_try'] = $now;
    $state['ok'] = $result['ok'];
    $state['message'] = $result['message'];
    $state['trigger'] = $trigger;
    if ($result['ok']) {
        $state['last_ok'] = $now;
        $state['items'] = $result['items'];
        $state['stats'] = $stats;
        $state['failures'] = 0;
        if ($fileSignature !== null) {
            $state['file_sig'] = $fileSignature;
        }
        if ($result['changed']) {
            $state['last_change'] = $now;
        }
    } else {
        $state['failures'] = (int) ($state['failures'] ?? 0) + 1;
    }
    $history = isset($state['history']) && is_array($state['history']) ? $state['history'] : [];
    array_unshift($history, ['at' => $now, 'ok' => $result['ok'], 'changed' => $result['changed'], 'items' => $result['items'],
        'trigger' => $trigger, 'message' => $result['message']]);
    $state['history'] = array_slice($history, 0, 15);
    try {
        sourceWriteFile(SOURCE_STATE_FILE, $state);
    } catch (Throwable $ignored) {
        // The sync itself is done; only its status line is out of date.
    }
    flock($lock, LOCK_UN);
    fclose($lock);
    return $result;
}

/**
 * "php index.php --catalog-source-sync [--now] [--force]": run by the server's
 * scheduler about every minute; syncs when the schedule says it is time (or
 * at once with --now). Exit code 0 unless a sync failed.
 */
function catalogSourceCli(array $argv): int
{
    @touch(SOURCE_TICK_FILE);
    $c = sourceConfig();
    $now = in_array('--now', $argv, true);
    if (!$c['enabled'] && !$now) {
        return 0;
    }
    $next = sourceNextRun($c, sourceState(), time());
    if (!$now && ($next === null || $next > time())) {
        return 0;
    }
    set_time_limit(900);
    $result = catalogSourceSync(in_array('--force', $argv, true), $now ? 'command' : 'schedule');
    fwrite($result['ok'] ? STDOUT : STDERR, date('Y-m-d H:i:s') . '  ' . $result['message'] . "\n");
    return $result['ok'] || !empty($result['busy']) ? 0 : 1;
}

/**
 * The Data source tab's live checks, as JSON, using the form as it is now
 * (saved password kept): op=test (connect, list tables), columns (column
 * names and the first rows) or preview (the first catalog items).
 */
function outputSourceProbe(): void
{
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    session_write_close();   // a slow database must not hold up the rest of the portal
    set_time_limit(300);
    $started = microtime(true);
    try {
        $c = sourceConfigFromInput($_POST, sourceConfig());
        $op = (string) ($_POST['op'] ?? '');
        if ($op === 'test') {
            sourceRequire($c, 'connect');
            if (sourceIsDatabase($c['kind'])) {
                [$pdo, $how] = sourceConnect($c);
                try {
                    $version = (string) $pdo->getAttribute(PDO::ATTR_SERVER_VERSION);
                } catch (Throwable $error) {
                    $version = '';
                }
                $tables = sourceTables($pdo, $c['kind']);
                $out = ['ok' => true, 'tables' => $tables, 'message' => 'Connected to ' . $how . ($version !== '' ? ' (version ' . $version . ')' : '')
                    . ' in ' . number_format(microtime(true) - $started, 1) . ' s.'
                    . ($tables ? ' Found ' . number_format(count($tables)) . (count($tables) >= 3000 ? '+' : '')
                        . (count($tables) === 1 ? ' table or view.' : ' tables and views.') : '')];
            } else {
                $rows = 0;
                $columns = sourceRead($c, 50, static function () use (&$rows): void {
                    $rows++;
                });
                $file = $c['kind'] === 'file' ? sourceLocalFile($c['file'], ['csv', 'txt', 'xlsx', 'xls']) : '';
                $out = ['ok' => true, 'columns' => $columns, 'message' => ($file !== ''
                    ? 'Found ' . basename($file) . ' (' . humanBytes((int) filesize($file)) . ', changed ' . date('M j, Y g:i A', (int) filemtime($file)) . ')'
                    : 'The web address answered') . ' with ' . count($columns) . ' columns.'];
            }
        } elseif ($op === 'columns') {
            sourceRequire($c, 'read');
            $rows = [];
            $columns = sourceRead($c, 10, static function (array $row) use (&$rows): void {
                $rows[] = $row;
            });
            $out = ['ok' => true, 'columns' => $columns, 'guess' => sourceGuessMap($columns, $c['map']),
                'rows' => array_map(static function (array $row) use ($columns): array {
                    return array_map(static function (string $column) use ($row): string {
                        $value = (string) ($row[$column] ?? '');
                        return function_exists('mb_strimwidth') ? mb_strimwidth($value, 0, 60, '…') : substr($value, 0, 60);
                    }, $columns);
                }, $rows),
                'message' => count($columns) . ' columns' . ($rows ? '; the first ' . count($rows) . ' rows are shown below.' : '. The source has no rows.')];
        } elseif ($op === 'preview') {
            sourceRequire($c, 'map');
            $items = [];
            $stats = ['rows' => 0, 'barcode' => 0, 'name' => 0, 'price' => 0];
            $columns = sourceRead($c, 200, static function (array $row) use ($c, &$items, &$stats): void {
                $stats['rows']++;
                [$item, $problem] = sourceItem($row, $c);
                if ($item === null) {
                    $stats[$problem]++;
                } elseif (count($items) < 25) {
                    $items[] = $item;
                }
            }, true);
            sourceCheckColumns($c, $columns);
            $ready = $stats['rows'] - $stats['barcode'] - $stats['name'] - $stats['price'];
            $skipped = sourceSkippedText($stats);
            $out = ['ok' => true, 'items' => $items, 'message' => 'Read ' . number_format($stats['rows']) . ' rows as a sample: '
                . number_format($ready) . ' ready to show' . ($skipped !== '' ? ', ' . $skipped : '') . '.'];
        } else {
            throw new RuntimeException('Unknown check.');
        }
    } catch (Throwable $error) {
        $out = ['ok' => false, 'message' => $error->getMessage()];
    }
    echo json_encode($out, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
    exit;
}

$configured = is_file(CONFIG_FILE);
$config = $configured ? require CONFIG_FILE : [];
if (!is_array($config)) {
    $config = [];
}
$slots = slots();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    try {
        requireCsrf();
        $action = isset($_POST['action']) ? (string) $_POST['action'] : '';

        if (!$configured && $action === 'setup') {
            $password = isset($_POST['password']) ? (string) $_POST['password'] : '';
            $confirm = isset($_POST['confirm']) ? (string) $_POST['confirm'] : '';
            if (strlen($password) < 10) {
                throw new RuntimeException('Use a password with at least 10 characters.');
            }
            if (!hash_equals($password, $confirm)) {
                throw new RuntimeException('The passwords do not match.');
            }
            $contents = "<?php\nreturn ['password_hash' => "
                . var_export(password_hash($password, PASSWORD_DEFAULT), true)
                . "];\n";
            if (file_put_contents(CONFIG_FILE, $contents, LOCK_EX) === false) {
                throw new RuntimeException('The folder is not writable. Set folder permissions in cPanel and try again.');
            }
            @chmod(CONFIG_FILE, 0640);
            $_SESSION['authenticated'] = true;
            session_regenerate_id(true);
            setFlash('Password created. The upload portal is ready.');
            redirectHome();
        }

        if ($configured && $action === 'login') {
            $password = isset($_POST['password']) ? (string) $_POST['password'] : '';
            $hash = isset($config['password_hash']) ? (string) $config['password_hash'] : '';
            if ($hash === '' || !password_verify($password, $hash)) {
                throw new RuntimeException('Incorrect password.');
            }
            $_SESSION['authenticated'] = true;
            session_regenerate_id(true);
            setFlash('Signed in.');
            redirectHome();
        }

        if ($action === 'logout') {
            unset($_SESSION['authenticated']);
            session_regenerate_id(true);
            setFlash('Signed out.');
            redirectHome();
        }

        if (empty($_SESSION['authenticated'])) {
            throw new RuntimeException('Sign in to manage files.');
        }

        if ($action === 'manual_item') {
            $updated = upsertCatalogItem($_POST);
            setFlash($updated ? 'Catalog item updated.' : 'Catalog item added.');
            redirectHome();
        }

        if ($action === 'delete_item') {
            $deleted = deleteCatalogItem((string) ($_POST['barcode'] ?? ''));
            setFlash($deleted ? 'Catalog item deleted.' : 'Catalog item was not found.');
            redirectHome();
        }

        if ($action === 'save_marquee') {
            $saved = saveMarqueeText((string) ($_POST['marquee_text'] ?? ''));
            setFlash($saved === ''
                ? 'Scrolling message cleared. Scanners hide the scrolling bar after their next sync.'
                : 'Scrolling message saved. Scanners show it after their next sync.');
            redirectHome();
        }

        if ($action === 'save_app_update_info') {
            saveAppUpdateInfo((string) ($_POST['app_version'] ?? ''),
                (string) ($_POST['app_changes'] ?? ''));
            setFlash('App update details saved.');
            redirectHome();
        }

        if ($action === 'source_probe') {
            outputSourceProbe();
        }

        if ($action === 'save_source') {
            $source = sourceConfigFromInput($_POST, sourceConfig());
            if ($source['enabled']) {
                sourceRequire($source, 'map');
            }
            $source['saved_at'] = time();
            sourceWriteFile(SOURCE_CONFIG_FILE, $source);
            if (($_POST['then'] ?? '') === 'sync') {
                session_write_close();
                set_time_limit(900);
                $result = catalogSourceSync(false, 'manual');
                session_start();
                setFlash('Data source saved. ' . $result['message'], $result['ok'] ? 'success' : 'error');
            } else {
                setFlash($source['enabled'] ? 'Data source saved. The first sync runs within a minute.'
                    : 'Data source saved. It is turned off, so the catalog is not synced.');
            }
            redirectHome();
        }

        if ($action === 'source_sync') {
            session_write_close();
            set_time_limit(900);
            $result = catalogSourceSync(!empty($_POST['force']), 'manual');
            session_start();   // closed above so a slow database does not hold up other pages
            setFlash($result['message'], $result['ok'] ? 'success' : 'error');
            redirectHome();
        }

        if ($action === 'source_enable') {
            $source = sourceConfig();
            $source['enabled'] = !empty($_POST['enabled']);
            if ($source['enabled']) {
                sourceRequire($source, 'map');
            }
            sourceWriteFile(SOURCE_CONFIG_FILE, $source);
            setFlash($source['enabled'] ? 'Data source turned on.' : 'Data source turned off. The catalog stays as it is.');
            redirectHome();
        }

        if ($action === 'save_catalog_columns') {
            saveCatalogColumns(isset($_POST['columns']) && is_array($_POST['columns']) ? $_POST['columns'] : []);
            setFlash('Column names saved. They apply to the next catalog upload.');
            redirectHome();
        }

        if ($action === 'request_sync') {
            $scope = (string) ($_POST['scope'] ?? '');
            requestDeviceSync($scope);
            setFlash(DEVICE_SYNC_SCOPES[$scope] . ' requested. '
                . ($scope === 'app'
                    ? 'Scanners that are on and connected show the update prompt within a few seconds.'
                    : 'Scanners that are on and connected start within a few seconds.'));
            redirectHome();
        }

        if ($action === 'forget_scanner') {
            forgetDevice(strtoupper(trim((string) ($_POST['serial'] ?? ''))));
            setFlash('Scanner removed from the list. It reappears when it checks in again.');
            redirectHome();
        }

        if ($action === 'set_media_order') {
            $positions = isset($_POST['pos']) && is_array($_POST['pos']) ? $_POST['pos'] : [];
            $order = effectiveMediaOrder();
            $ranked = [];
            foreach ($order as $index => $token) {
                $raw = isset($positions[$token]) ? trim((string) $positions[$token]) : '';
                // Fall back to the current position when a box is blank or invalid,
                // and use the current index to break ties (stable ordering).
                $ranked[] = [
                    'token' => $token,
                    'pos' => is_numeric($raw) ? (float) $raw : ($index + 1),
                    'index' => $index,
                ];
            }
            usort($ranked, static function (array $a, array $b): int {
                return $a['pos'] <=> $b['pos'] ?: $a['index'] <=> $b['index'];
            });
            saveMediaOrder(array_column($ranked, 'token'));
            setFlash('Playback order updated.');
            redirectHome();
        }

        if ($action === 'save_promo') {
            [$promo, $error] = validatePromo($_POST);
            if ($promo === null) {
                throw new RuntimeException($error);
            }
            $promos = readPromos();
            $replaced = false;
            foreach ($promos as $index => $existing) {
                if ($existing['id'] === $promo['id']) {
                    $promos[$index] = $promo;
                    $replaced = true;
                }
            }
            if (!$replaced) {
                $promos[] = $promo;
            }
            writePromos($promos);
            setFlash($replaced ? 'Promo updated.' : 'Promo added.');
            redirectHome();
        }

        if ($action === 'delete_promo') {
            $id = (string) ($_POST['id'] ?? '');
            $promos = readPromos();
            $kept = array_values(array_filter($promos, static function (array $promo) use ($id): bool {
                return $promo['id'] !== $id;
            }));
            writePromos($kept);
            setFlash(count($kept) < count($promos) ? 'Promo deleted.' : 'Promo was not found.');
            redirectHome();
        }

        if ($action === 'purge_ended_promos') {
            $today = date('Y-m-d');
            $promos = readPromos();
            $kept = array_values(array_filter($promos, static function (array $promo) use ($today): bool {
                return promoStatus($promo, $today) !== 'ended';
            }));
            writePromos($kept);
            $removed = count($promos) - count($kept);
            setFlash('Removed ' . $removed . ' ended promo' . ($removed === 1 ? '' : 's') . '.');
            redirectHome();
        }

        if ($action === 'save_design') {
            // manage[key] ticks a setting as server-managed; value[key] is its value.
            $manage = isset($_POST['manage']) && is_array($_POST['manage']) ? $_POST['manage'] : [];
            $values = isset($_POST['value']) && is_array($_POST['value']) ? $_POST['value'] : [];
            $design = [];
            foreach (designFields() as $key => $field) {
                if (empty($manage[$key])) {
                    continue;
                }
                $raw = $field['type'] === 'bool' ? !empty($values[$key]) : ($values[$key] ?? '');
                $value = normalizeDesignValue($field, $raw);
                if ($value === null) {
                    if ($field['type'] === 'choice') {
                        throw new RuntimeException('Check "' . $field['label'] . '": choose one of the options.');
                    }
                    throw new RuntimeException('Check "' . $field['label'] . '": '
                        . ($field['type'] === 'color' ? 'use a colour like #FDE047.'
                            : 'use a number from ' . $field['min'] . ' to ' . $field['max'] . '.'));
                }
                $design[$key] = $value;
            }
            writeDesign($design);
            setFlash($design
                ? 'Design saved. Scanners apply it on their next sync.'
                : 'Design cleared. Each scanner keeps its own settings.');
            redirectHome();
        }

        if ($action === 'upload_promos') {
            $upload = $_FILES['promos_file'] ?? null;
            if (!is_array($upload) || ($upload['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
                throw new RuntimeException('Choose a promos CSV file to upload.');
            }
            $tempPath = (string) $upload['tmp_name'];
            $ext = strtolower(pathinfo((string) $upload['name'], PATHINFO_EXTENSION));
            if (!is_uploaded_file($tempPath) || !in_array($ext, ['csv', 'txt'], true)
                    || !textFileIsSafe($tempPath)) {
                throw new RuntimeException('Upload a valid CSV promos file.');
            }
            [$promos, $skipped] = parsePromosCsv($tempPath);
            writePromos($promos);
            $message = 'Imported ' . count($promos) . ' promo' . (count($promos) === 1 ? '' : 's') . '.';
            if ($skipped) {
                $message .= ' Skipped ' . count($skipped) . ' row' . (count($skipped) === 1 ? '' : 's')
                    . ' that could not be read (line ' . implode(', ', array_slice($skipped, 0, 10))
                    . (count($skipped) > 10 ? ', …' : '') . ').';
            }
            setFlash($message, $skipped ? 'error' : 'success');
            redirectHome();
        }

        $slotKey = isset($_POST['slot']) ? (string) $_POST['slot'] : '';
        if (!isset($slots[$slotKey])) {
            throw new RuntimeException('Choose a valid content type.');
        }
        $slot = $slots[$slotKey];

        if ($action === 'remove') {
            if ($slotKey === 'display_media' && isset($_POST['file'])) {
                $name = basename(str_replace('\\', '/', (string) $_POST['file']));
                if (!isSupportedScheduleMediaName($name)) {
                    throw new RuntimeException('Choose a valid uploaded media file.');
                }
                @unlink(__DIR__ . '/' . $name);
                unregisterScheduledMedia($name);
            } elseif ($slotKey === 'marquee') {
                // Deleting marquee.txt would leave the old message on the scanners.
                saveMarqueeText('');
                setFlash('Scrolling message cleared. Scanners hide the scrolling bar after their next sync.');
                redirectHome();
            } else {
                foreach ($slot['files'] as $name) {
                    @unlink(__DIR__ . '/' . $name);
                }
            }
            setFlash($slot['label'] . ' removed.');
            redirectHome();
        }

        if ($action === 'rename') {
            if ($slotKey !== 'display_media') {
                throw new RuntimeException('This file cannot be renamed.');
            }
            $oldName = basename(str_replace('\\', '/', (string) ($_POST['file'] ?? '')));
            $newName = basename(str_replace('\\', '/', trim((string) ($_POST['new_name'] ?? ''))));
            if (!isSupportedScheduleMediaName($oldName) || !is_file(__DIR__ . '/' . $oldName)) {
                throw new RuntimeException('Choose a valid uploaded media file.');
            }
            if (!isDisplayMediaName($newName) || !isSupportedScheduleMediaName($newName)) {
                throw new RuntimeException('Rename the file to [3CODE_]MMDDYY[_SECONDS].ext, like 010126_20.mp4 or 000_010126_20.png.');
            }
            if ($oldName === $newName) {
                setFlash('The filename was not changed.');
                redirectHome();
            }
            $oldExt = strtolower(pathinfo($oldName, PATHINFO_EXTENSION));
            $newExt = strtolower(pathinfo($newName, PATHINFO_EXTENSION));
            if ($oldExt !== $newExt) {
                throw new RuntimeException('Keep the same file extension when renaming.');
            }
            $target = __DIR__ . '/' . $newName;
            if (is_file($target)) {
                throw new RuntimeException('A file with that name already exists.');
            }
            if (!@rename(__DIR__ . '/' . $oldName, $target)) {
                throw new RuntimeException('The server could not rename the file.');
            }
            unregisterScheduledMedia($oldName);
            registerScheduledMedia($newName);
            setFlash($oldName . ' renamed to ' . $newName . '.');
            redirectHome();
        }

        if ($action !== 'upload' || !isset($_FILES['upload'])) {
            throw new RuntimeException('Choose a file before uploading.');
        }

        $file = $_FILES['upload'];
        $uploads = [];
        if (isset($file['error']) && is_array($file['error'])) {
            foreach ($file['error'] as $index => $errorCode) {
                $uploads[] = [
                    'error' => (int) $errorCode,
                    'size' => isset($file['size'][$index]) ? (int) $file['size'][$index] : 0,
                    'tmp_name' => isset($file['tmp_name'][$index]) ? (string) $file['tmp_name'][$index] : '',
                    'name' => isset($file['name'][$index]) ? (string) $file['name'][$index] : '',
                ];
            }
        } else {
            $uploads[] = [
                'error' => isset($file['error']) ? (int) $file['error'] : UPLOAD_ERR_NO_FILE,
                'size' => isset($file['size']) ? (int) $file['size'] : 0,
                'tmp_name' => isset($file['tmp_name']) ? (string) $file['tmp_name'] : '',
                'name' => isset($file['name']) ? (string) $file['name'] : '',
            ];
        }
        if (count($uploads) > 1 && empty($slot['multiple'])) {
            throw new RuntimeException('Upload one file for this content type.');
        }

        $saved = [];
        $compressedCount = 0;
        foreach ($uploads as $upload) {
            if ($upload['error'] !== UPLOAD_ERR_OK) {
                throw new RuntimeException(uploadError((int) $upload['error']));
            }
            $size = (int) $upload['size'];
            if ($size <= 0 || $size > $slot['max']) {
                throw new RuntimeException('Each file must be smaller than ' . humanBytes($slot['max']) . '.');
            }
            $tempPath = (string) $upload['tmp_name'];
            $originalName = (string) $upload['name'];
            if (!is_uploaded_file($tempPath)) {
                throw new RuntimeException('The server did not receive a valid upload.');
            }
            if ($slotKey === 'display_media') {
                $kind = isset($_POST['media_kind']) ? (string) $_POST['media_kind'] : '';
                $ext = strtolower(pathinfo($originalName, PATHINFO_EXTENSION));
                if ($kind === 'image' && !in_array($ext, ['jpg', 'jpeg', 'png'], true)) {
                    throw new RuntimeException('Choose a JPG or PNG image.');
                }
                if ($kind === 'video' && $ext !== 'mp4') {
                    throw new RuntimeException('Choose an MP4 video.');
                }
            }
            $targetName = chooseTarget($slotKey, $tempPath, $originalName);
            if ($slotKey === 'display_media' && !isDisplayMediaName($targetName)) {
                // Not named with an end date: schedule it for one month from today,
                // so it plays without anyone having to rename it.
                $targetName = autoScheduleMediaName(pathinfo($targetName, PATHINFO_EXTENSION));
                $autoScheduled = true;
            }
            if ($slotKey === 'catalog') {
                $mergeCatalog = isset($_POST['catalog_mode']) && $_POST['catalog_mode'] === 'merge';
                $catalogStats = replaceCatalogUpload($tempPath, $originalName, $targetName, $mergeCatalog);
                $targetName = $catalogStats['target'];
            } elseif (replaceUpload($targetName, $tempPath, $slotKey)) {
                $compressedCount++;
            }
            if ($slotKey === 'display_media') {
                registerScheduledMedia($targetName);
            }
            if (empty($slot['multiple'])) {
                removeAlternateFiles($slot, $targetName);
            }
            $saved[] = $targetName;
        }

        $message = $slot['label'] . ' updated as ' . implode(', ', $saved) . '.';
        if ($compressedCount > 0) {
            $message .= ' Optimized ' . $compressedCount . ' file' . ($compressedCount === 1 ? '' : 's') . '.';
        }
        if (!empty($autoScheduled)) {
            $message .= ' Scheduled automatically to play for one month, through '
                . defaultScheduleEnd()->format('F j, Y')
                . '. To change the end date or the seconds per turn, rename the file below.';
        }
        if (isset($catalogStats)) {
            if ($catalogStats['excel']) {
                $message .= ' Converted from Excel.';
            }
            $merged = $catalogStats['merged'];
            if ($merged !== null) {
                $message .= ' Added ' . number_format($merged['added']) . ' new, updated ' . number_format($merged['updated'])
                    . ($merged['unchanged'] > 0 ? ', and ' . number_format($merged['unchanged']) . ' already matched' : '')
                    . '. The rest of the catalog was kept: ' . number_format($merged['ready']) . ' items ready to sync.';
            } else {
                $message .= ' ' . number_format($catalogStats['valid']) . ' items ready to sync.';
                if ($catalogStats['previous'] !== null && $catalogStats['previous'] > 0) {
                    $message .= ' It replaced the previous catalog of ' . number_format($catalogStats['previous']) . ' items.';
                }
            }
            if ($catalogStats['scientific'] > 0) {
                $message .= ' Skipped ' . number_format($catalogStats['scientific'])
                    . ' rows whose barcode is in scientific notation (e.g. 4.80653E+11), usually caused by saving the CSV in Excel.'
                    . ' Re-export with the barcode column formatted as Text to include them.';
            }
            if ($catalogStats['invalid'] > 0) {
                $message .= ' Skipped ' . number_format($catalogStats['invalid'])
                    . ' other rows with a missing barcode, name, or valid price.';
            }
            if ($catalogStats['numericBarcodes'] > 0) {
                $message .= ' Note: ' . number_format($catalogStats['numericBarcodes'])
                    . ' barcodes were stored as numbers in Excel, so any leading zeros were already lost.'
                    . ' If some barcodes should start with 0, format the barcode column as Text in Excel, retype them, and upload again.';
            }
        }
        setFlash($message);
        redirectHome();
    } catch (Throwable $error) {
        setFlash($error->getMessage(), 'error');
        redirectHome();
    }
}

$authenticated = !empty($_SESSION['authenticated']);
$catalogQuery = isset($_GET['q']) ? trim((string) $_GET['q']) : '';
$catalogPageNumber = max(1, isset($_GET['page']) ? (int) $_GET['page'] : 1);

/**
 * JSON feed behind the live catalog search box. Same query/page contract as the
 * plain-HTML form, so the page keeps working with scripting turned off.
 */
/** The Scanners tab's status block on its own, fetched every few seconds to refresh it in place. */
if (isset($_GET['scanner_status'])) {
    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-store');
    if (!$authenticated) {
        http_response_code(401);
        exit;
    }
    echo renderScannerStatus(scannerStatus());
    exit;
}

if (isset($_GET['catalog_search'])) {
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    if (!$authenticated) {
        http_response_code(403);
        echo json_encode(['error' => 'Sign in to search the catalog.']);
        exit;
    }
    try {
        $result = catalogPage($catalogQuery, $catalogPageNumber, CATALOG_PAGE_SIZE);
        $result['q'] = $catalogQuery;
        echo json_encode($result, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    } catch (Throwable $error) {
        http_response_code(500);
        echo json_encode(['error' => $error->getMessage()]);
    }
    exit;
}

/**
 * Catalog details for the Promos tab, loaded after the page so the catalog scan
 * doesn't slow every page load: all promo barcodes (?promo_products=1) or one
 * barcode typed into the promo form (?product_lookup=...).
 */
if (isset($_GET['promo_products']) || isset($_GET['product_lookup'])) {
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    if (!$authenticated) {
        http_response_code(403);
        echo json_encode(['error' => 'Sign in first.']);
        exit;
    }
    try {
        if (isset($_GET['product_lookup'])) {
            $code = preg_replace('/\s+/', '', (string) $_GET['product_lookup']);
            $item = $code === '' ? null : findCatalogItem($code);
            $result = $item ? ['found' => true, 'name' => $item['name'], 'price' => $item['price']]
                : ['found' => false];
        } else {
            $result = catalogLookup(array_values(array_unique(array_column(readPromos(), 'barcode'))));
        }
        echo json_encode((object) $result, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    } catch (Throwable $error) {
        http_response_code(500);
        echo json_encode(['error' => $error->getMessage()]);
    }
    exit;
}

$flash = takeFlash();
$promos = $authenticated ? readPromos() : [];
$design = $authenticated ? readDesign() : [];
$promoToday = date('Y-m-d');
$editPromoId = isset($_GET['promo_edit']) ? preg_replace('/[^a-f0-9]/', '', (string) $_GET['promo_edit']) : '';
$editingPromo = null;
foreach ($promos as $promo) {
    if ($editPromoId !== '' && $promo['id'] === $editPromoId) {
        $editingPromo = $promo;
    }
}
$catalogView = $authenticated ? catalogPage($catalogQuery, $catalogPageNumber, CATALOG_PAGE_SIZE) : null;
$editBarcode = isset($_GET['edit']) ? preg_replace('/\s+/', '', (string) $_GET['edit']) : '';
$editingItem = $authenticated && $editBarcode !== '' ? findCatalogItem($editBarcode) : null;
$appUpdateInfo = $authenticated ? appUpdateInfo() : ['version' => '', 'changes' => ''];
$sourceConfig = $authenticated ? sourceConfig() : sourceDefaults();
$sourceState = $authenticated ? sourceState() : [];
$sourceReady = $authenticated && sourceConfigured($sourceConfig);
$idleMediaOrder = $authenticated ? effectiveMediaOrder() : [];
// Which tab opens first: jump to Catalog when editing an item or searching,
// otherwise let the browser restore the last-used tab (JS/localStorage).
$forcedTab = ($editingItem || $catalogQuery !== '') ? 'catalog' : ($editingPromo ? 'promos' : '');
?>
<!doctype html>
<html lang="en" data-theme="light">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="color-scheme" content="light dark">
    <meta name="robots" content="noindex, nofollow">
    <title>Price Verifier Content</title>
    <link rel="stylesheet" href="<?= h(asset('assets/app.css')) ?>">
    <?php if (is_file(__DIR__ . '/logo.png')): ?>
        <link rel="icon" href="logo.png">
    <?php endif; ?>
    <script>
        // Set before first paint: avoids a light flash for dark-theme users and
        // lets CSS hide the no-JS fallbacks straight away.
        document.documentElement.className += ' js';
        (function () {
            var saved = null;
            try { saved = localStorage.getItem('pvTheme'); } catch (e) {}
            var dark = saved ? saved === 'dark'
                : window.matchMedia('(prefers-color-scheme: dark)').matches;
            document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
        })();
    </script>
</head>
<body>
<header class="site-header">
    <div class="header-inner">
        <div class="brand">
            <span class="brand-mark"><?= icon('tag') ?></span>
            <div class="brand-text">
                <h1>Price Verifier Content</h1>
                <p class="subtle">Catalog &amp; display media</p>
            </div>
        </div>
        <div class="header-actions">
            <button class="icon-btn" type="button" data-theme-toggle aria-label="Switch theme">
                <span data-theme-icon="light" hidden><?= icon('sun') ?></span>
                <span data-theme-icon="dark" hidden><?= icon('moon') ?></span>
            </button>
            <?php if ($authenticated): ?>
                <?php if (is_file(__DIR__ . '/price-verifier.apk')): ?>
                    <a class="button secondary" href="price-verifier.apk" download><?= icon('download') ?>App</a>
                <?php endif; ?>
                <form method="post">
                    <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                    <input type="hidden" name="action" value="logout">
                    <button class="ghost" type="submit">Sign out</button>
                </form>
            <?php endif; ?>
        </div>
    </div>
</header>
<main>
    <?php if ($flash): ?>
        <div class="notice <?= h($flash['type']) ?>" role="status">
            <span class="notice-text"><?= h($flash['message']) ?></span>
            <button class="icon-btn js-only" type="button" data-dismiss aria-label="Dismiss"><?= icon('close') ?></button>
        </div>
    <?php endif; ?>

    <?php if (!$configured): ?>
        <section class="panel">
            <span class="panel-icon"><?= icon('lock') ?></span>
            <h2>Create upload password</h2>
            <p class="subtle">Set the password your client will use to manage verifier content.</p>
            <form method="post">
                <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                <input type="hidden" name="action" value="setup">
                <label for="password">Password</label>
                <input id="password" name="password" type="password" minlength="10" required autofocus
                       autocomplete="new-password" placeholder="At least 10 characters">
                <label for="confirm">Confirm password</label>
                <input id="confirm" name="confirm" type="password" minlength="10" required
                       autocomplete="new-password">
                <div class="actions"><button class="primary" type="submit">Create portal</button></div>
            </form>
        </section>

    <?php elseif (!$authenticated): ?>
        <section class="panel">
            <span class="panel-icon"><?= icon('lock') ?></span>
            <h2>Sign in</h2>
            <p class="subtle">Manage the files used by the price verifier display.</p>
            <form method="post">
                <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                <input type="hidden" name="action" value="login">
                <label for="password">Password</label>
                <input id="password" name="password" type="password" required autofocus
                       autocomplete="current-password">
                <div class="actions"><button class="primary" type="submit">Sign in</button></div>
            </form>
        </section>

    <?php else: ?>
        <div class="page-intro">
            <h2>Verifier files</h2>
            <p class="subtle">Upload a file to replace the current version. Images are resized and recompressed
                automatically; videos are compressed only when this hosting account has ffmpeg enabled.</p>
        </div>

        <nav class="tabs" role="tablist" aria-label="Sections">
            <button type="button" class="tab-btn" data-tab="media" role="tab" aria-controls="tab-media">Display media</button>
            <button type="button" class="tab-btn" data-tab="catalog" role="tab" aria-controls="tab-catalog">Catalog</button>
            <button type="button" class="tab-btn" data-tab="source" role="tab" aria-controls="tab-source">Data source</button>
            <button type="button" class="tab-btn" data-tab="promos" role="tab" aria-controls="tab-promos">Promos</button>
            <button type="button" class="tab-btn" data-tab="design" role="tab" aria-controls="tab-design">Design</button>
            <button type="button" class="tab-btn" data-tab="devices" role="tab" aria-controls="tab-devices">App update</button>
            <button type="button" class="tab-btn" data-tab="scanners" role="tab" aria-controls="tab-scanners">Scanners</button>
        </nav>

        <!-- ============================== Display media ============================== -->
        <div class="tab-panel active" id="tab-media" data-tab="media" role="tabpanel">
            <div class="grid">
                <?php foreach ($slots as $slotKey => $slot): ?>
                    <?php
                    $files = currentFiles($slot);
                    if (!$files) {
                        $statusText = 'No file uploaded';
                    } elseif (count($files) > 1) {
                        $statusText = count($files) . ' files uploaded';
                    } else {
                        $statusText = $files[0]['name'] . ' · ' . humanBytes($files[0]['size']);
                    }
                    if ($slotKey === 'marquee') {
                        $marqueeMessage = marqueeText();
                        if ($files) {
                            $statusText = $marqueeMessage === '' ? 'Empty: scrolling bar hidden' : $marqueeMessage;
                        } else {
                            $statusText = 'No message set';
                        }
                    }
                    ?>
                    <details class="card"<?= $files ? ' data-has-file="1"' : '' ?>>
                        <summary class="card-head">
                            <span class="card-head-main">
                                <span class="slot-icon"><?= slotIcon($slotKey) ?></span>
                                <span class="card-head-text">
                                    <h2><?= h($slot['label']) ?></h2>
                                    <span class="card-status" title="<?= h($statusText) ?>"><?= h($statusText) ?></span>
                                </span>
                            </span>
                            <span class="card-head-right">
                                <span class="chevron"><?= icon('chevron') ?></span>
                            </span>
                        </summary>
                        <div class="card-body">
                            <div class="card-meta">
                                <span class="tag neutral"><?= h($slot['hint']) ?></span>
                                <span class="subtle">Max <?= h(humanBytes((int) $slot['max'])) ?></span>
                            </div>
                            <?php if (!$files): ?>
                                <div class="file-empty">Nothing uploaded yet</div>
                            <?php else: ?>
                                <div class="file-list">
                                    <?php foreach ($files as $file): ?>
                                        <div class="file-row">
                                            <span class="file-name"><?= h($file['name']) ?></span>
                                            <span class="file-meta"><?= h(humanBytes($file['size'])) ?> &middot; <?= h(date('M j, Y H:i', $file['time'])) ?></span>
                                        </div>
                                    <?php endforeach; ?>
                                </div>
                            <?php endif; ?>

                            <?php if (!empty($slot['format'])): ?>
                                <div class="format-box">
                                    <strong><?= $slotKey === 'catalog' ? 'Required catalog format' : 'Scheduled media format' ?></strong>
                                    <code><?= h($slot['format']) ?></code>
                                    <?php if ($slotKey === 'catalog'): ?>
                                        <code>4800014141081,Bottled Water 1L,BTL,Beverages,28.00,ITEM-004</code>
                                    <?php elseif (!empty($slot['example'])): ?>
                                        <code class="wrap"><?= h($slot['example']) ?></code>
                                    <?php endif; ?>
                                </div>
                            <?php endif; ?>

                            <?php if ($slotKey === 'display_media'): ?>
                                <?php foreach ([
                                    ['kind' => 'image', 'label' => 'Scheduled images', 'accept' => '.jpg,.jpeg,.png,image/jpeg,image/png', 'button' => 'Upload images'],
                                    ['kind' => 'video', 'label' => 'Scheduled videos', 'accept' => '.mp4,video/mp4', 'button' => 'Upload videos'],
                                ] as $group): ?>
                                    <form method="post" enctype="multipart/form-data" data-upload>
                                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                                        <input type="hidden" name="action" value="upload">
                                        <input type="hidden" name="slot" value="<?= h($slotKey) ?>">
                                        <input type="hidden" name="media_kind" value="<?= h($group['kind']) ?>">
                                        <label for="up-<?= h($slotKey . '-' . $group['kind']) ?>"><?= h($group['label']) ?></label>
                                        <input id="up-<?= h($slotKey . '-' . $group['kind']) ?>" name="upload[]" type="file"
                                               accept="<?= h($group['accept']) ?>" multiple required>
                                        <p class="subtle" data-file-status></p>
                                        <div class="actions"><button class="primary" type="submit"><?= h($group['button']) ?></button></div>
                                    </form>
                                <?php endforeach; ?>
                            <?php else: ?>
                                <?php if ($slotKey === 'marquee'): ?>
                                    <form method="post">
                                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                                        <input type="hidden" name="action" value="save_marquee">
                                        <label for="marquee-text">Message</label>
                                        <textarea id="marquee-text" name="marquee_text" rows="3" maxlength="2000"
                                                  placeholder="Type the message that scrolls along the bottom of the scanner"><?= h($marqueeMessage) ?></textarea>
                                        <p class="subtle">Leave it empty and save to hide the scrolling bar on the scanners.</p>
                                        <div class="actions"><button class="primary" type="submit">Save message</button></div>
                                    </form>
                                    <p class="or-divider"><span>or upload a text file</span></p>
                                <?php endif; ?>
                                <form method="post" enctype="multipart/form-data" data-upload<?php if ($slotKey === 'catalog'): ?>
                                      data-catalog-upload<?= $catalogView && $catalogQuery === '' ? ' data-catalog-count="' . (int) $catalogView['total'] . '"' : '' ?><?php endif; ?>>
                                    <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                                    <input type="hidden" name="action" value="upload">
                                    <input type="hidden" name="slot" value="<?= h($slotKey) ?>">
                                    <?php if ($slotKey === 'catalog'): ?>
                                        <div class="choice-list" role="radiogroup" aria-label="What the upload does">
                                            <label class="choice">
                                                <input type="radio" name="catalog_mode" value="replace" checked>
                                                <span><strong>Replace the whole catalog</strong>
                                                    <small>The file becomes the full product list. Items that are not in the file are removed from the server.</small></span>
                                            </label>
                                            <label class="choice">
                                                <input type="radio" name="catalog_mode" value="merge">
                                                <span><strong>Add and update only</strong>
                                                    <small>Items in the file are added, or updated when the barcode already exists. Everything else in the catalog stays.</small></span>
                                            </label>
                                        </div>
                                    <?php endif; ?>
                                    <label for="up-<?= h($slotKey) ?>"><?= $slotKey === 'catalog' ? 'Catalog file' : ($slotKey === 'marquee' ? 'Text file (.txt)' : 'Replace file') ?></label>
                                    <input id="up-<?= h($slotKey) ?>" name="<?= !empty($slot['multiple']) ? 'upload[]' : 'upload' ?>"
                                           type="file" accept="<?= h($slot['accept']) ?>" <?= !empty($slot['multiple']) ? 'multiple' : '' ?> required>
                                    <p class="subtle" data-file-status></p>
                                    <div class="actions"><button class="primary" type="submit">Upload</button></div>
                                </form>
                            <?php endif; ?>

                            <?php if ($files): ?>
                                <?php if ($slotKey === 'display_media'): ?>
                                    <div class="manage-block">
                                        <h3>Manage uploaded media</h3>
                                        <?php foreach ($files as $file): ?>
                                            <?php $fid = md5($file['name']); ?>
                                            <div class="manage-item">
                                                <form method="post">
                                                    <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                                                    <input type="hidden" name="action" value="rename">
                                                    <input type="hidden" name="slot" value="<?= h($slotKey) ?>">
                                                    <input type="hidden" name="file" value="<?= h($file['name']) ?>">
                                                    <label for="rename-<?= h($fid) ?>"><?= h($file['name']) ?>
                                                        <a class="file-view" href="<?= h(rawurlencode($file['name'])) ?>" target="_blank" rel="noopener">View</a></label>
                                                    <div class="field-row">
                                                        <input id="rename-<?= h($fid) ?>" name="new_name" type="text"
                                                               value="<?= h($file['name']) ?>" spellcheck="false" required>
                                                        <button class="secondary btn-sm" type="submit">Rename</button>
                                                    </div>
                                                </form>
                                                <form method="post" data-confirm="Remove <?= h($file['name']) ?>?">
                                                    <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                                                    <input type="hidden" name="action" value="remove">
                                                    <input type="hidden" name="slot" value="<?= h($slotKey) ?>">
                                                    <input type="hidden" name="file" value="<?= h($file['name']) ?>">
                                                    <div class="actions" style="margin-top:8px"><button class="danger btn-sm" type="submit">Remove file</button></div>
                                                </form>
                                            </div>
                                        <?php endforeach; ?>
                                    </div>
                                <?php elseif ($slotKey === 'marquee'): ?>
                                    <?php if ($marqueeMessage !== ''): ?>
                                        <form method="post" data-confirm="Clear the scrolling message? Scanners will hide the scrolling bar after their next sync.">
                                            <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                                            <input type="hidden" name="action" value="remove">
                                            <input type="hidden" name="slot" value="<?= h($slotKey) ?>">
                                            <div class="actions"><button class="danger btn-sm" type="submit">Clear message</button></div>
                                        </form>
                                    <?php endif; ?>
                                <?php else: ?>
                                    <form method="post" data-confirm="Remove the current <?= h(strtolower($slot['label'])) ?>?">
                                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                                        <input type="hidden" name="action" value="remove">
                                        <input type="hidden" name="slot" value="<?= h($slotKey) ?>">
                                        <div class="actions"><button class="danger btn-sm" type="submit">Remove current file</button></div>
                                    </form>
                                <?php endif; ?>
                            <?php endif; ?>
                        </div>
                    </details>
                <?php endforeach; ?>
            </div>

            <dialog class="modal" id="media-modal">
                <div class="modal-head">
                    <h2 data-modal-title>&nbsp;</h2>
                    <button class="icon-btn" type="button" data-modal-close aria-label="Close"><?= icon('close') ?></button>
                </div>
                <div class="modal-body" data-modal-body></div>
            </dialog>

            <section class="card">
                <div class="card-head">
                    <div>
                        <h2>Playback order</h2>
                        <p class="subtle">Drag a row, or type a position and press Tab, then Save. Devices apply this
                            order on their next sync unless a device sets its own.</p>
                    </div>
                    <span class="tag" data-order-meta><?= count($idleMediaOrder) ?> items</span>
                </div>
                <?php if (!$idleMediaOrder): ?>
                    <p class="subtle order-empty">Upload a banner, idle video, or scheduled media to arrange the playback order.</p>
                <?php else: ?>
                    <form method="post">
                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                        <input type="hidden" name="action" value="set_media_order">
                        <div class="order-toolbar">
                            <div class="search-field" data-filled="0">
                                <span class="search-icon"><?= icon('search') ?></span>
                                <input type="search" data-order-filter placeholder="Filter playback items&hellip;"
                                       aria-label="Filter playback items" autocomplete="off" spellcheck="false">
                                <button class="search-clear" type="button" data-order-filter-clear aria-label="Clear filter">&times;</button>
                            </div>
                            <button class="primary" type="submit">Save order</button>
                        </div>
                        <div class="order-list" data-order-list>
                            <?php foreach ($idleMediaOrder as $position => $token): ?>
                                <?php $label = idleTokenLabel($token); ?>
                                <div class="order-row" data-search="<?= h($label . ' ' . $token . ' ' . idleTokenKind($token)) ?>">
                                    <span class="drag-handle js-only" title="Drag to reorder"><?= icon('grip') ?></span>
                                    <input class="order-input" type="number" min="1" step="1"
                                           name="pos[<?= h($token) ?>]" value="<?= (int) $position + 1 ?>"
                                           aria-label="Play position for <?= h($label) ?>">
                                    <span class="order-label"><?= h($label) ?></span>
                                    <span class="order-kind"><?= h(idleTokenKind($token)) ?></span>
                                </div>
                            <?php endforeach; ?>
                        </div>
                        <div class="actions" style="margin-top:14px">
                            <button class="primary" type="submit">Save order</button>
                            <span class="subtle js-only">Tip: Alt + &uarr;/&darr; moves the focused row.</span>
                        </div>
                    </form>
                <?php endif; ?>
            </section>
        </div><!-- /tab-media -->

        <!-- ================================= Catalog ================================ -->
        <div class="tab-panel" id="tab-catalog" data-tab="catalog" role="tabpanel">
            <dialog class="modal" id="item-modal" open<?= $editingItem ? ' data-autopopen="1"' : '' ?>>
                <div class="modal-head">
                    <div>
                        <h2><?= $editingItem ? 'Edit catalog item' : 'Add a catalog item' ?></h2>
                        <p class="subtle" data-item-modal-subtitle><?= $editingItem
                            ? 'Editing the selected hosted item.'
                            : 'An existing barcode will be updated in the hosted catalog.' ?></p>
                    </div>
                    <button class="icon-btn js-only" type="button" data-modal-close aria-label="Close"><?= icon('close') ?></button>
                </div>
                <div class="modal-body">
                    <form method="post" autocomplete="off" id="item-form">
                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                        <input type="hidden" name="action" value="manual_item">
                        <div class="form-grid">
                            <div>
                                <label for="item-barcode">Barcode</label>
                                <input id="item-barcode" name="barcode" type="text" inputmode="numeric" spellcheck="false"
                                       value="<?= h((string) ($editingItem['barcode'] ?? '')) ?>" required>
                            </div>
                            <div>
                                <label for="item-name">Product name</label>
                                <input id="item-name" name="name" type="text"
                                       value="<?= h((string) ($editingItem['name'] ?? '')) ?>" required>
                            </div>
                            <div>
                                <label for="item-uom">UOM</label>
                                <input id="item-uom" name="uom" type="text" placeholder="PACK"
                                       value="<?= h((string) ($editingItem['uom'] ?? '')) ?>">
                            </div>
                            <div>
                                <label for="item-category">Category</label>
                                <input id="item-category" name="category" type="text" placeholder="Grocery"
                                       value="<?= h((string) ($editingItem['category'] ?? '')) ?>">
                            </div>
                            <div>
                                <label for="item-price">Price</label>
                                <input id="item-price" name="price" type="number" min="0" step="0.01" placeholder="0.00"
                                       value="<?= h((string) ($editingItem['price'] ?? '')) ?>" required>
                            </div>
                            <div>
                                <label for="item-code">Item code</label>
                                <input id="item-code" name="item_code" type="text" spellcheck="false" placeholder="ITEM-001"
                                       value="<?= h((string) ($editingItem['item_code'] ?? '')) ?>">
                            </div>
                        </div>
                        <div class="actions" style="margin-top:16px">
                            <button class="primary" type="submit" data-item-modal-submit><?= $editingItem ? 'Save item' : 'Add or update item' ?></button>
                            <?php if ($editingItem): ?>
                                <a class="button secondary no-js-only" href="./">Cancel edit</a>
                            <?php endif; ?>
                            <button class="secondary js-only" type="button" data-modal-close>Cancel</button>
                        </div>
                    </form>
                    <form method="post" data-item-delete-form<?= $editingItem ? '' : ' hidden' ?>
                          data-confirm="Delete <?= h((string) ($editingItem['name'] ?? 'this item')) ?> from the catalog?">
                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                        <input type="hidden" name="action" value="delete_item">
                        <input type="hidden" name="barcode" data-item-delete-barcode value="<?= h((string) ($editingItem['barcode'] ?? '')) ?>">
                        <button class="danger btn-sm" type="submit">Delete this item</button>
                    </form>
                </div>
            </dialog>

            <?php
            $catalogFile = activeCatalogPath();
            $catalogUpdated = is_file($catalogFile) ? (filemtime($catalogFile) ?: 0) : 0;
            $catalogFirst = ((int) $catalogView['page'] - 1) * CATALOG_PAGE_SIZE + 1;
            $catalogLast = $catalogFirst + (int) $catalogView['shown'] - 1;
            $catalogPrevHref = '?q=' . rawurlencode($catalogQuery) . '&page=' . ((int) $catalogView['page'] - 1);
            $catalogNextHref = '?q=' . rawurlencode($catalogQuery) . '&page=' . ((int) $catalogView['page'] + 1);
            ?>
            <?php if ($sourceReady && $sourceConfig['enabled']): ?>
                <p class="source-message is-info source-catalog-note"><?= icon('database') ?>
                    <span>This catalog is synced from <?= h(sourceDescribe($sourceConfig)) ?> (<?= h(strtolower(sourceSchedules()[$sourceConfig['schedule']])) ?>).
                    <?= $sourceConfig['mode'] === 'replace' ? 'Changes made here are replaced at the next sync.' : 'Products from the source are updated at each sync; the others are kept.' ?></span></p>
            <?php endif; ?>
            <section class="card catalog-card" data-catalog
                     data-page="<?= (int) $catalogView['page'] ?>"
                     data-pages="<?= (int) $catalogView['pages'] ?>"
                     data-page-size="<?= (int) CATALOG_PAGE_SIZE ?>"
                     data-editing="<?= h((string) ($editingItem['barcode'] ?? '')) ?>">
                <div class="catalog-head">
                    <div class="catalog-title">
                        <span class="catalog-icon"><?= icon('box') ?></span>
                        <div>
                            <h2>Products</h2>
                            <p class="subtle"><?= h(basename($catalogFile)) ?><?php if ($catalogUpdated): ?>
                                &middot; updated <time data-local datetime="<?= h(date('c', $catalogUpdated)) ?>"><?= h(date('M j, Y g:i A', $catalogUpdated)) ?></time>
                            <?php endif; ?></p>
                        </div>
                    </div>
                    <button class="primary js-only" type="button" data-open-add-item><?= icon('plus') ?> Add product</button>
                </div>
                <div class="list-tools">
                    <form method="get" role="search">
                        <div class="search-field" data-filled="0">
                            <span class="search-icon"><?= icon('search') ?></span>
                            <input name="q" type="search" data-catalog-search value="<?= h($catalogQuery) ?>"
                                   placeholder="Search barcode, name, category or code"
                                   aria-label="Search catalog" autocomplete="off" spellcheck="false">
                            <kbd class="search-kbd js-only" title="Press / to search">/</kbd>
                            <button class="search-clear" type="button" data-search-clear aria-label="Clear search">&times;</button>
                        </div>
                        <button class="primary no-js-only" type="submit">Search</button>
                    </form>
                    <?php if ($catalogQuery !== ''): ?>
                        <a class="button secondary no-js-only" href="./">Clear</a>
                    <?php endif; ?>
                    <span class="result-meta" data-catalog-meta aria-live="polite"><?php
                        if ((int) $catalogView['total'] === 0) {
                            echo 'No results';
                        } else {
                            echo number_format($catalogFirst) . '&ndash;' . number_format($catalogLast)
                                . ' of ' . number_format((int) $catalogView['total'])
                                . ($catalogQuery !== '' ? ' matches' : ' products');
                        }
                    ?></span>
                </div>
                <div class="table-wrap">
                    <table class="catalog-table">
                        <thead>
                        <tr>
                            <th>Product</th>
                            <th>Category</th>
                            <th>Unit</th>
                            <th>Item code</th>
                            <th class="num">Price</th>
                            <th><span class="sr-only">Actions</span></th>
                        </tr>
                        </thead>
                        <tbody data-catalog-body>
                        <?php if (!$catalogView['items']): ?>
                            <tr class="empty-row"><td colspan="6">
                                <div class="empty-state">
                                    <span class="empty-icon"><?= icon($catalogQuery !== '' ? 'search' : 'box') ?></span>
                                    <?php if ($catalogQuery !== ''): ?>
                                        <strong>No products match &ldquo;<?= h($catalogQuery) ?>&rdquo;</strong>
                                        <span class="subtle">Check the spelling, or search with part of the barcode or name.</span>
                                    <?php else: ?>
                                        <strong>No products yet</strong>
                                        <span class="subtle">Upload catalog.csv on the Display media tab, or add a product.</span>
                                    <?php endif; ?>
                                </div>
                            </td></tr>
                        <?php else: ?>
                            <?php foreach ($catalogView['items'] as $item): ?>
                                <tr class="catalog-row<?= $editingItem && $item['barcode'] === $editingItem['barcode'] ? ' is-editing' : '' ?>"
                                    data-barcode="<?= h($item['barcode']) ?>" data-name="<?= h($item['name']) ?>"
                                    data-uom="<?= h($item['uom']) ?>" data-category="<?= h($item['category']) ?>"
                                    data-price="<?= h($item['price']) ?>" data-item-code="<?= h($item['item_code']) ?>">
                                    <td class="cell-product"><span class="product-name"><?= h($item['name']) ?></span><span class="product-barcode"><?= h($item['barcode']) ?></span></td>
                                    <td class="cell-category"><?php if ($item['category'] !== ''): ?><span class="chip"><?= h($item['category']) ?></span><?php endif; ?></td>
                                    <td class="cell-uom"><?= h($item['uom']) ?></td>
                                    <td class="cell-code"><?= h($item['item_code']) ?></td>
                                    <td class="cell-price"><?= h(catalogPriceLabel($item['price'])) ?></td>
                                    <td class="actions-cell">
                                        <a class="icon-btn row-edit" data-edit-item title="Edit" aria-label="Edit <?= h($item['name']) ?>"
                                           href="?edit=<?= h(rawurlencode($item['barcode'])) ?>&amp;q=<?= h(rawurlencode($catalogQuery)) ?>&amp;page=<?= (int) $catalogView['page'] ?>"><?= icon('edit') ?></a>
                                    </td>
                                </tr>
                            <?php endforeach; ?>
                        <?php endif; ?>
                        </tbody>
                    </table>
                </div>
                <div class="pager" data-catalog-pager<?= (int) $catalogView['pages'] <= 1 ? ' hidden' : '' ?>>
                    <span class="subtle">Page <?= number_format((int) $catalogView['page']) ?> of <?= number_format((int) $catalogView['pages']) ?></span>
                    <div class="actions">
                        <?php if ($catalogView['page'] > 1): ?>
                            <a class="button secondary btn-sm" href="<?= h($catalogPrevHref) ?>"><?= icon('chevron-left') ?> Previous</a>
                        <?php else: ?>
                            <span class="button secondary btn-sm" aria-disabled="true"><?= icon('chevron-left') ?> Previous</span>
                        <?php endif; ?>
                        <?php if ($catalogView['page'] < $catalogView['pages']): ?>
                            <a class="button secondary btn-sm" href="<?= h($catalogNextHref) ?>">Next <?= icon('chevron') ?></a>
                        <?php else: ?>
                            <span class="button secondary btn-sm" aria-disabled="true">Next <?= icon('chevron') ?></span>
                        <?php endif; ?>
                    </div>
                </div>
            </section>

            <?php $customColumns = catalogCustomAliases(); $builtInColumns = catalogDefaultAliases(); ?>
            <section class="card">
                <div class="card-head">
                    <div>
                        <h2>Column names</h2>
                        <p class="subtle">Header names accepted in catalog uploads (CSV, TXT and Excel). The built-in names always work.
                            Add your own below, separated by commas &mdash; for example <em>Product Name</em>. Upper and lower case do not matter.</p>
                    </div>
                    <span class="tag neutral">Upload settings</span>
                </div>
                <form method="post" autocomplete="off">
                    <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                    <input type="hidden" name="action" value="save_catalog_columns">
                    <div class="form-grid">
                        <?php foreach (catalogFieldLabels() as $field => $label): ?>
                            <div>
                                <label for="col-<?= h($field) ?>"><?= h($label) ?><?= in_array($field, ['barcode', 'name', 'price'], true) ? ' (required column)' : '' ?></label>
                                <input id="col-<?= h($field) ?>" name="columns[<?= h($field) ?>]" type="text" spellcheck="false"
                                       value="<?= h(implode(', ', $customColumns[$field])) ?>" placeholder="Extra names, separated by commas">
                                <p class="subtle" style="margin-top:4px">Built in: <?= h(implode(', ', $builtInColumns[$field])) ?></p>
                            </div>
                        <?php endforeach; ?>
                    </div>
                    <div class="actions" style="margin-top:14px">
                        <button class="primary" type="submit">Save column names</button>
                    </div>
                </form>
            </section>
        </div><!-- /tab-catalog -->

        <!-- ================================ Data source ================================ -->
        <?php
        $sourceKindList = sourceKinds();
        $sourceFieldKinds = [];
        foreach ($sourceKindList as $kindKey => $kindInfo) {
            foreach ($kindInfo['fields'] as $field) {
                foreach ($field === 'login' ? ['username', 'password'] : [$field] as $name) {
                    $sourceFieldKinds[$name][] = $kindKey;
                }
            }
        }
        $sourceKindsOf = static function (string $field) use ($sourceFieldKinds): string {
            return implode(' ', $sourceFieldKinds[$field] ?? []);
        };
        $sourceNext = sourceNextRun($sourceConfig, $sourceState, time());
        $sourceTick = is_file(SOURCE_TICK_FILE) ? (int) filemtime(SOURCE_TICK_FILE) : 0;
        $sourceSchedulerQuiet = $sourceReady && $sourceConfig['enabled'] && $sourceConfig['schedule'] !== 'manual'
            && time() - $sourceTick > 300;
        $sourceLastTry = (int) ($sourceState['last_try'] ?? 0);
        $sourceOk = !empty($sourceState['ok']);
        $sourcePill = !$sourceConfig['enabled'] ? ['ended', 'Off']
            : ($sourceLastTry > 0 && !$sourceOk ? ['failed', 'Last sync failed'] : ['active', 'On']);
        $sourceTime = static function (int $at): string {
            return '<time data-local datetime="' . h(date('c', $at)) . '">' . h(date('M j, Y g:i A', $at)) . '</time>';
        };
        ?>
        <div class="tab-panel" id="tab-source" data-tab="source" role="tabpanel">
            <section class="card catalog-card source-status">
                <div class="catalog-head">
                    <div class="catalog-title">
                        <span class="catalog-icon"><?= icon('database') ?></span>
                        <div>
                            <h2>Data source</h2>
                            <p class="subtle"><?= $sourceReady
                                ? h(sourceDescribe($sourceConfig))
                                : 'Optional: read the products straight from the store\'s POS database or export file, instead of uploading the catalog.' ?></p>
                        </div>
                    </div>
                    <?php if ($sourceReady): ?>
                        <span class="status-pill <?= $sourcePill[0] ?>"><?= h($sourcePill[1]) ?></span>
                    <?php else: ?>
                        <span class="tag neutral">Not set up</span>
                    <?php endif; ?>
                </div>
                <?php if ($sourceReady): ?>
                    <div class="source-facts">
                        <div><span>Schedule</span><strong><?= h(sourceSchedules()[$sourceConfig['schedule']]) ?><?= $sourceConfig['schedule'] === 'daily' ? ' at ' . h($sourceConfig['daily_at']) : '' ?></strong></div>
                        <div><span>Last sync</span><strong><?= $sourceLastTry > 0 ? $sourceTime($sourceLastTry) : 'Not yet' ?></strong></div>
                        <div><span>Next sync</span><strong><?= !$sourceConfig['enabled'] ? 'Turned off'
                            : ($sourceNext === null ? 'When you press Sync now' : ($sourceNext <= time() ? 'Within a minute' : $sourceTime($sourceNext))) ?></strong></div>
                        <div><span>Products</span><strong><?= isset($sourceState['items']) ? number_format((int) $sourceState['items']) : '&mdash;' ?></strong></div>
                    </div>
                    <?php if (!empty($sourceState['message'])): ?>
                        <p class="source-message <?= $sourceOk ? 'is-ok' : 'is-error' ?>"><?= h((string) $sourceState['message']) ?></p>
                    <?php endif; ?>
                    <?php if ($sourceSchedulerQuiet): ?>
                        <p class="source-message is-warn">The automatic sync has not checked in<?= $sourceTick ? ' since ' . $sourceTime($sourceTick) : ' yet' ?>.
                            Servers set up with the Price Verifier installer run it by themselves; restart the server if this stays.
                            On other hosting, add a cron job that runs every minute:
                            <code>php <?= h(__DIR__ . DIRECTORY_SEPARATOR . 'index.php') ?> --catalog-source-sync</code></p>
                    <?php endif; ?>
                    <div class="source-actions">
                        <form method="post" class="source-sync-form" data-source-sync>
                            <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                            <input type="hidden" name="action" value="source_sync">
                            <button class="primary" type="submit">Sync now</button>
                            <label class="inline-check"><input type="checkbox" name="force" value="1"> Skip the safety stop</label>
                        </form>
                        <form method="post">
                            <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                            <input type="hidden" name="action" value="source_enable">
                            <input type="hidden" name="enabled" value="<?= $sourceConfig['enabled'] ? '0' : '1' ?>">
                            <button class="secondary" type="submit"><?= $sourceConfig['enabled'] ? 'Turn off' : 'Turn on' ?></button>
                        </form>
                    </div>
                    <?php if (!empty($sourceState['history'])): ?>
                        <details class="source-history">
                            <summary>Recent syncs</summary>
                            <div class="table-wrap">
                                <table class="catalog-table">
                                    <thead><tr><th>When</th><th>Started by</th><th>Result</th></tr></thead>
                                    <tbody>
                                    <?php foreach ($sourceState['history'] as $run): ?>
                                        <tr>
                                            <td class="cell-code"><?= $sourceTime((int) ($run['at'] ?? 0)) ?></td>
                                            <td class="cell-uom"><?= h(['schedule' => 'Schedule', 'manual' => 'Portal', 'command' => 'Command'][$run['trigger'] ?? ''] ?? '') ?></td>
                                            <td><span class="status-pill <?= !empty($run['ok']) ? (!empty($run['changed']) ? 'active' : 'ended') : 'failed' ?>"><?=
                                                !empty($run['ok']) ? (!empty($run['changed']) ? 'Updated' : 'No changes') : 'Failed' ?></span>
                                                <span class="source-run-text"><?= h((string) ($run['message'] ?? '')) ?></span></td>
                                        </tr>
                                    <?php endforeach; ?>
                                    </tbody>
                                </table>
                            </div>
                        </details>
                    <?php endif; ?>
                <?php endif; ?>
            </section>

            <form method="post" class="card source-form" data-source-form autocomplete="off">
                <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                <input type="hidden" name="action" value="save_source">

                <fieldset class="source-step">
                    <legend><span class="step-num">1</span> Where are the products?</legend>
                    <div class="source-kinds" role="radiogroup" aria-label="Kind of source">
                        <?php foreach ($sourceKindList as $kindKey => $kindInfo): [$kindReady] = sourceKindReady($kindKey); ?>
                            <label class="source-kind<?= $sourceConfig['kind'] === $kindKey ? ' is-checked' : '' ?><?= $kindReady ? '' : ' is-unavailable' ?>"
                                   data-port="<?= (int) $kindInfo['port'] ?>">
                                <input type="radio" name="kind" value="<?= h($kindKey) ?>"<?= $sourceConfig['kind'] === $kindKey ? ' checked' : '' ?> data-source-kind>
                                <span class="source-kind-name"><?= h($kindInfo['label']) ?></span>
                                <span class="source-kind-about"><?= h($kindInfo['about']) ?></span>
                                <?php if (!$kindReady): ?><span class="source-kind-flag">Not on this server</span><?php endif; ?>
                            </label>
                        <?php endforeach; ?>
                    </div>
                    <?php foreach ($sourceKindList as $kindKey => $kindInfo): [$kindReady, $kindWhy] = sourceKindReady($kindKey); ?>
                        <p class="source-needs" data-for-kind="<?= h($kindKey) ?>"><?= h($kindReady ? $kindInfo['needs'] : $kindWhy) ?></p>
                    <?php endforeach; ?>
                </fieldset>

                <fieldset class="source-step">
                    <legend><span class="step-num">2</span> Connection</legend>
                    <div class="form-grid">
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('host')) ?>">
                            <label for="source-host">Server address</label>
                            <input id="source-host" name="host" type="text" spellcheck="false" placeholder="192.168.1.20 or POS-PC" value="<?= h($sourceConfig['host']) ?>">
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('instance')) ?>">
                            <label for="source-instance">Instance (optional)</label>
                            <input id="source-instance" name="instance" type="text" spellcheck="false" placeholder="SQLEXPRESS" value="<?= h($sourceConfig['instance']) ?>">
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('port')) ?>">
                            <label for="source-port">Port</label>
                            <input id="source-port" name="port" type="text" inputmode="numeric" placeholder="Usual port" value="<?= h($sourceConfig['port']) ?>" data-source-port>
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('database')) ?>"
                             data-labels="<?= h((string) json_encode(['oracle' => 'Service name', 'firebird' => 'Database file or alias', 'hana' => 'Tenant database (optional)'])) ?>">
                            <label for="source-database" data-default="Database">Database</label>
                            <input id="source-database" name="database" type="text" spellcheck="false" value="<?= h($sourceConfig['database']) ?>">
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('username')) ?>">
                            <label for="source-username">User name</label>
                            <input id="source-username" name="username" type="text" spellcheck="false" autocomplete="off" value="<?= h($sourceConfig['username']) ?>" data-source-login>
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('password')) ?>">
                            <label for="source-password">Password</label>
                            <input id="source-password" name="password" type="password" autocomplete="new-password" data-source-login
                                   placeholder="<?= $sourceConfig['password'] !== '' ? 'Saved; leave blank to keep it' : '' ?>">
                            <?php if ($sourceConfig['password'] !== ''): ?>
                                <label class="inline-check"><input type="checkbox" name="password_clear" value="1"> Remove the saved password</label>
                            <?php endif; ?>
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('driver')) ?>">
                            <label for="source-driver">ODBC driver</label>
                            <input id="source-driver" name="driver" type="text" spellcheck="false" list="source-drivers" placeholder="Automatic" value="<?= h($sourceConfig['driver']) ?>">
                            <datalist id="source-drivers"><?php foreach (sourceInstalledDrivers() as $driverName): ?><option value="<?= h($driverName) ?>"><?php endforeach; ?></datalist>
                        </div>
                        <div class="source-field source-wide" data-kinds="<?= h($sourceKindsOf('dsn')) ?>">
                            <label for="source-dsn">Data source name (DSN) or connection string</label>
                            <input id="source-dsn" name="dsn" type="text" spellcheck="false" placeholder="POSDATA  or  Driver={...};Server=...;Database=..." value="<?= h($sourceConfig['dsn']) ?>">
                        </div>
                        <div class="source-field source-wide" data-kinds="<?= h($sourceKindsOf('file')) ?>"
                             data-placeholders="<?= h((string) json_encode(['access' => 'C:\\POS\\Data\\store.accdb', 'sqlite' => 'C:\\POS\\Data\\pos.db', 'file' => 'C:\\POS\\Export\\items*.csv'])) ?>">
                            <label for="source-file">File path on this server</label>
                            <input id="source-file" name="file" type="text" spellcheck="false" value="<?= h($sourceConfig['file']) ?>">
                            <p class="subtle source-needs" data-for-kind="file">Put * in the name to take the newest matching file, like items_*.csv. CSV, TXT, XLSX or XLS.</p>
                        </div>
                        <div class="source-field source-wide" data-kinds="<?= h($sourceKindsOf('url')) ?>">
                            <label for="source-url">Web address</label>
                            <input id="source-url" name="url" type="text" spellcheck="false" placeholder="https://..." value="<?= h($sourceConfig['url']) ?>">
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('header')) ?>">
                            <label for="source-header">Request header (optional)</label>
                            <input id="source-header" name="header" type="password" autocomplete="off" spellcheck="false"
                                   placeholder="<?= $sourceConfig['header'] !== '' ? 'Saved; leave blank to keep it' : 'Authorization: Bearer ...' ?>">
                            <?php if ($sourceConfig['header'] !== ''): ?>
                                <label class="inline-check"><input type="checkbox" name="header_clear" value="1"> Remove the saved header</label>
                            <?php endif; ?>
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('format')) ?>">
                            <label for="source-format">Format</label>
                            <select id="source-format" name="format">
                                <option value="csv"<?= $sourceConfig['format'] === 'csv' ? ' selected' : '' ?>>CSV</option>
                                <option value="json"<?= $sourceConfig['format'] === 'json' ? ' selected' : '' ?>>JSON</option>
                            </select>
                        </div>
                        <div class="source-field" data-kinds="<?= h($sourceKindsOf('records')) ?>">
                            <label for="source-records">Where the items are (JSON, optional)</label>
                            <input id="source-records" name="records" type="text" spellcheck="false" placeholder="data.items" value="<?= h($sourceConfig['records']) ?>">
                        </div>
                    </div>
                    <div class="source-checks">
                        <label class="inline-check source-field" data-kinds="<?= h($sourceKindsOf('windows_auth')) ?>"><input type="checkbox" name="windows_auth" value="1"<?= $sourceConfig['windows_auth'] ? ' checked' : '' ?> data-source-windows-auth>
                            Sign in with Windows instead (as this server's system account)</label>
                        <label class="inline-check source-field" data-kinds="<?= h($sourceKindsOf('encrypt')) ?>"><input type="checkbox" name="encrypt" value="1"<?= $sourceConfig['encrypt'] ? ' checked' : '' ?>>
                            Encrypt the connection (the database server needs TLS 1.2)</label>
                    </div>
                    <div class="actions source-probe js-only">
                        <button class="secondary" type="button" data-source-op="test">Test connection</button>
                        <p class="source-result" data-source-result="test" aria-live="polite"></p>
                    </div>
                </fieldset>

                <fieldset class="source-step">
                    <legend><span class="step-num">3</span> What to read</legend>
                    <div class="source-db-only">
                        <div class="choice-list source-read">
                            <label class="choice"><input type="radio" name="read" value="table"<?= $sourceConfig['read'] === 'table' ? ' checked' : '' ?> data-source-read>
                                <span><strong>A table or view</strong><small>Pick it from the list after testing the connection.</small></span></label>
                            <label class="choice"><input type="radio" name="read" value="query"<?= $sourceConfig['read'] === 'query' ? ' checked' : '' ?> data-source-read>
                                <span><strong>A custom query</strong><small>A SELECT that joins tables, for POS systems that keep barcodes or prices in separate tables.</small></span></label>
                        </div>
                        <div class="form-grid" data-read="table">
                            <div>
                                <label for="source-table">Table or view</label>
                                <input id="source-table" name="table" type="text" spellcheck="false" list="source-tables" placeholder="dbo.Items" value="<?= h($sourceConfig['table']) ?>">
                                <datalist id="source-tables"></datalist>
                            </div>
                            <div class="source-wide">
                                <label for="source-filter">Only rows where (optional)</label>
                                <input id="source-filter" name="filter" type="text" spellcheck="false" placeholder="Active = 1 AND Price > 0" value="<?= h($sourceConfig['filter']) ?>">
                            </div>
                        </div>
                        <div data-read="query">
                            <label for="source-query">Query</label>
                            <textarea id="source-query" name="query" spellcheck="false" rows="8"
                                      placeholder="SELECT barcode, description, retail_price FROM items WHERE active = 1"><?= h($sourceConfig['query']) ?></textarea>
                            <div class="actions" style="margin-top:8px">
                                <button class="secondary btn-sm js-only" type="button" data-source-example>Use the SAP Business One example</button>
                                <span class="subtle">Only a single SELECT is accepted. Use a database user that can only read.</span>
                            </div>
                        </div>
                    </div>
                    <div class="actions source-probe js-only">
                        <button class="secondary" type="button" data-source-op="columns">Load columns</button>
                        <p class="source-result" data-source-result="columns" aria-live="polite"></p>
                    </div>
                    <div class="table-wrap source-table" data-source-rows hidden></div>
                </fieldset>

                <fieldset class="source-step">
                    <legend><span class="step-num">4</span> Match the columns</legend>
                    <datalist id="source-columns"></datalist>
                    <div class="form-grid">
                        <?php foreach (catalogFieldLabels() as $field => $label): ?>
                            <div>
                                <label for="source-map-<?= h($field) ?>"><?= h($label) ?><?= in_array($field, ['barcode', 'name', 'price'], true) ? '' : ' (optional)' ?></label>
                                <input id="source-map-<?= h($field) ?>" name="map[<?= h($field) ?>]" type="text" spellcheck="false" list="source-columns"
                                       value="<?= h($sourceConfig['map'][$field]) ?>" data-source-map="<?= h($field) ?>">
                            </div>
                        <?php endforeach; ?>
                        <div>
                            <label for="source-price-unit">Prices are stored in</label>
                            <select id="source-price-unit" name="price_unit">
                                <option value="peso"<?= $sourceConfig['price_unit'] === 'peso' ? ' selected' : '' ?>>Pesos (14.75)</option>
                                <option value="cent"<?= $sourceConfig['price_unit'] === 'cent' ? ' selected' : '' ?>>Centavos (1475)</option>
                            </select>
                        </div>
                        <div>
                            <label for="source-encoding">Text encoding</label>
                            <select id="source-encoding" name="encoding">
                                <option value="auto"<?= $sourceConfig['encoding'] === 'auto' ? ' selected' : '' ?>>Automatic</option>
                                <option value="utf-8"<?= $sourceConfig['encoding'] === 'utf-8' ? ' selected' : '' ?>>UTF-8</option>
                                <option value="windows-1252"<?= $sourceConfig['encoding'] === 'windows-1252' ? ' selected' : '' ?>>Windows-1252 (older Windows POS)</option>
                            </select>
                        </div>
                    </div>
                    <div class="source-checks">
                        <label class="inline-check"><input type="checkbox" name="add_vat" value="1"<?= $sourceConfig['add_vat'] ? ' checked' : '' ?>>
                            The prices exclude VAT: add 12% before showing them</label>
                    </div>
                    <div class="actions source-probe js-only">
                        <button class="secondary" type="button" data-source-op="preview">Preview products</button>
                        <p class="source-result" data-source-result="preview" aria-live="polite"></p>
                    </div>
                    <div class="table-wrap source-table" data-source-preview hidden></div>
                </fieldset>

                <fieldset class="source-step">
                    <legend><span class="step-num">5</span> Keeping in sync</legend>
                    <label class="inline-check source-enable"><input type="checkbox" name="enabled" value="1"<?= $sourceConfig['enabled'] ? ' checked' : '' ?>>
                        <strong>Turn on: keep the catalog in sync with this source</strong></label>
                    <div class="form-grid">
                        <div>
                            <label for="source-schedule">Sync</label>
                            <select id="source-schedule" name="schedule" data-source-schedule>
                                <?php foreach (sourceSchedules() as $scheduleKey => $scheduleLabel): ?>
                                    <option value="<?= h((string) $scheduleKey) ?>"<?= $sourceConfig['schedule'] === (string) $scheduleKey ? ' selected' : '' ?>><?= h($scheduleLabel) ?></option>
                                <?php endforeach; ?>
                            </select>
                        </div>
                        <div data-source-daily>
                            <label for="source-daily-at">At</label>
                            <input id="source-daily-at" name="daily_at" type="time" value="<?= h($sourceConfig['daily_at']) ?>">
                        </div>
                        <div>
                            <label for="source-max-drop">Safety stop (%)</label>
                            <input id="source-max-drop" name="max_drop" type="number" min="0" max="100" step="1" value="<?= (int) $sourceConfig['max_drop'] ?>">
                        </div>
                    </div>
                    <p class="subtle source-note">The safety stop keeps the current catalog when a sync would leave that many percent fewer products
                        (0 turns it off), for example while the POS is rebuilding its tables. A source with no products never replaces the catalog.</p>
                    <div class="choice-list source-mode">
                        <label class="choice"><input type="radio" name="mode" value="replace"<?= $sourceConfig['mode'] === 'replace' ? ' checked' : '' ?>>
                            <span><strong>The source is the whole catalog</strong><small>Products removed from the POS disappear from the scanners. Edits made on the Catalog tab are replaced at the next sync.</small></span></label>
                        <label class="choice"><input type="radio" name="mode" value="merge"<?= $sourceConfig['mode'] === 'merge' ? ' checked' : '' ?>>
                            <span><strong>Add and update only</strong><small>Products in the source are added or updated by barcode; everything else in the catalog is kept.</small></span></label>
                    </div>
                    <label class="inline-check"><input type="checkbox" name="notify" value="1"<?= $sourceConfig['notify'] ? ' checked' : '' ?>>
                        Tell the scanners to update as soon as a sync changes the catalog</label>
                </fieldset>

                <div class="actions source-save">
                    <button class="primary" type="submit" name="then" value="save">Save</button>
                    <button class="secondary" type="submit" name="then" value="sync">Save and sync now</button>
                </div>
            </form>

            <section class="card">
                <div class="card-head">
                    <div>
                        <h2>A read-only database user</h2>
                        <p class="subtle">Ask the POS administrator for a user that can only read the product tables. Examples (change the names and the password):</p>
                    </div>
                    <span class="tag neutral">Good practice</span>
                </div>
                <details class="source-help"><summary>MySQL / MariaDB</summary>
<pre>CREATE USER 'pricecheck'@'%' IDENTIFIED BY 'a-long-password';
GRANT SELECT ON posdb.items TO 'pricecheck'@'%';</pre></details>
                <details class="source-help"><summary>SQL Server (and SAP Business One on SQL Server)</summary>
<pre>CREATE LOGIN pricecheck WITH PASSWORD = 'a-long-password';
USE POSDB;
CREATE USER pricecheck FOR LOGIN pricecheck;
ALTER ROLE db_datareader ADD MEMBER pricecheck;</pre>
                    <p class="subtle">SQL Server must accept TCP/IP connections and SQL Server sign-in (mixed mode). For a named instance such as SQLEXPRESS,
                        leave the port blank and keep the SQL Server Browser service running. On SQL Server 2005 use EXEC sp_addrolemember 'db_datareader', 'pricecheck'.</p></details>
                <details class="source-help"><summary>PostgreSQL</summary>
<pre>CREATE ROLE pricecheck LOGIN PASSWORD 'a-long-password';
GRANT USAGE ON SCHEMA public TO pricecheck;
GRANT SELECT ON public.products TO pricecheck;</pre></details>
                <details class="source-help"><summary>SAP HANA</summary>
<pre>CREATE RESTRICTED USER PRICECHECK PASSWORD "A-long-password1" NO FORCE_FIRST_PASSWORD_CHANGE;
ALTER USER PRICECHECK ENABLE CLIENT CONNECT;
GRANT SELECT ON SCHEMA "SBO_COMPANY" TO PRICECHECK;</pre></details>
                <details class="source-help"><summary>Oracle</summary>
<pre>CREATE USER pricecheck IDENTIFIED BY "a-long-password";
GRANT CREATE SESSION TO pricecheck;
GRANT SELECT ON pos.items TO pricecheck;</pre></details>
            </section>
        </div><!-- /tab-source -->

        <!-- ================================= Promos ================================= -->
        <?php
        $promoCounts = ['all' => count($promos), 'active' => 0, 'scheduled' => 0, 'ended' => 0];
        foreach ($promos as $promo) {
            $promoCounts[promoStatus($promo, $promoToday)]++;
        }
        $promoForm = $editingPromo ?? ['id' => '', 'barcode' => '', 'label' => 'promo', 'type' => 'percent',
            'value' => '', 'buy' => '1', 'take' => '1', 'start' => $promoToday,
            'end' => date('Y-m-d', strtotime('+7 days'))];
        ?>
        <div class="tab-panel" id="tab-promos" data-tab="promos" role="tabpanel">
            <dialog class="modal" id="promo-modal" open<?= $editingPromo ? ' data-autopopen="1"' : '' ?>>
                <div class="modal-head">
                    <div>
                        <h2 data-promo-modal-title><?= $editingPromo ? 'Edit promo' : 'Add a promo' ?></h2>
                        <p class="subtle">Shows on the price check with the promo price and a banner.</p>
                    </div>
                    <button class="icon-btn js-only" type="button" data-modal-close aria-label="Close"><?= icon('close') ?></button>
                </div>
                <div class="modal-body">
                    <form method="post" autocomplete="off" id="promo-form">
                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                        <input type="hidden" name="action" value="save_promo">
                        <input type="hidden" name="id" value="<?= h($promoForm['id']) ?>">

                        <label for="promo-barcode">Product barcode</label>
                        <input id="promo-barcode" name="barcode" type="text" inputmode="numeric" spellcheck="false"
                               value="<?= h($promoForm['barcode']) ?>" required>
                        <p class="field-hint" data-promo-product aria-live="polite"></p>

                        <label>Show as</label>
                        <div class="segmented" role="radiogroup">
                            <label class="seg-option"><input type="radio" name="label" value="promo"<?= $promoForm['label'] !== 'special' ? ' checked' : '' ?>><span>Promo</span></label>
                            <label class="seg-option special"><input type="radio" name="label" value="special"<?= $promoForm['label'] === 'special' ? ' checked' : '' ?>><span>Special price</span></label>
                        </div>

                        <div class="form-grid promo-grid">
                            <div>
                                <label for="promo-type">Type</label>
                                <select id="promo-type" name="type">
                                    <?php foreach (promoTypeLabels() as $key => $text): ?>
                                        <option value="<?= h($key) ?>"<?= $promoForm['type'] === $key ? ' selected' : '' ?>><?= h($text) ?></option>
                                    <?php endforeach; ?>
                                </select>
                            </div>
                            <div data-promo-value-field>
                                <label for="promo-value" data-promo-value-label>Value</label>
                                <input id="promo-value" name="value" type="number" min="0" step="0.01"
                                       value="<?= h($promoForm['value']) ?>">
                            </div>
                            <div data-promo-qty-field>
                                <label for="promo-buy">Buy</label>
                                <input id="promo-buy" name="buy" type="number" min="1" max="999" step="1" value="<?= h($promoForm['buy'] ?: '1') ?>">
                            </div>
                            <div data-promo-qty-field>
                                <label for="promo-take">Take</label>
                                <input id="promo-take" name="take" type="number" min="1" max="999" step="1" value="<?= h($promoForm['take'] ?: '1') ?>">
                            </div>
                            <div>
                                <label for="promo-start">Start date</label>
                                <input id="promo-start" name="start" type="date" value="<?= h($promoForm['start']) ?>" required>
                            </div>
                            <div>
                                <label for="promo-end">End date</label>
                                <input id="promo-end" name="end" type="date" value="<?= h($promoForm['end']) ?>" required>
                            </div>
                        </div>
                        <p class="promo-preview" data-promo-preview aria-live="polite"></p>

                        <div class="actions" style="margin-top:16px">
                            <button class="primary" type="submit" data-promo-submit><?= $editingPromo ? 'Save promo' : 'Add promo' ?></button>
                            <?php if ($editingPromo): ?>
                                <a class="button secondary no-js-only" href="./">Cancel edit</a>
                            <?php endif; ?>
                            <button class="secondary js-only" type="button" data-modal-close>Cancel</button>
                        </div>
                    </form>
                    <form method="post" data-promo-delete-form<?= $editingPromo ? '' : ' hidden' ?> data-confirm="Delete this promo?">
                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                        <input type="hidden" name="action" value="delete_promo">
                        <input type="hidden" name="id" data-promo-delete-id value="<?= h($promoForm['id']) ?>">
                        <button class="danger btn-sm" type="submit">Delete this promo</button>
                    </form>
                </div>
            </dialog>

            <section class="card catalog-card promo-card" data-promos>
                <div class="catalog-head">
                    <div class="catalog-title">
                        <span class="catalog-icon promo-icon"><?= icon('tag') ?></span>
                        <div>
                            <h2>Promos &amp; special prices</h2>
                            <p class="subtle">promos.csv &middot; <?= number_format($promoCounts['all']) ?> total &middot;
                                <span data-promo-active-count><?= number_format($promoCounts['active']) ?></span> active today</p>
                        </div>
                    </div>
                    <button class="primary js-only" type="button" data-open-add-promo><?= icon('plus') ?> Add promo</button>
                </div>

                <div class="list-tools">
                    <div class="search-field" data-filled="0">
                        <span class="search-icon"><?= icon('search') ?></span>
                        <input type="search" data-promo-search placeholder="Search barcode, product or deal"
                               aria-label="Search promos" autocomplete="off" spellcheck="false">
                        <button class="search-clear" type="button" data-search-clear aria-label="Clear search">&times;</button>
                    </div>
                    <div class="filter-chips" role="group" aria-label="Filter by status">
                        <?php foreach (['all' => 'All', 'active' => 'Active', 'scheduled' => 'Scheduled', 'ended' => 'Ended'] as $key => $text): ?>
                            <button type="button" class="chip-btn<?= $key === 'all' ? ' active' : '' ?>" data-promo-filter="<?= $key ?>">
                                <?= $text ?> <span data-promo-count="<?= $key ?>"><?= number_format($promoCounts[$key]) ?></span>
                            </button>
                        <?php endforeach; ?>
                    </div>
                </div>

                <div class="table-wrap">
                    <table class="catalog-table promo-table">
                        <thead>
                        <tr>
                            <th>Product</th>
                            <th>Deal</th>
                            <th class="num">Price</th>
                            <th>Dates</th>
                            <th>Status</th>
                            <th><span class="sr-only">Actions</span></th>
                        </tr>
                        </thead>
                        <tbody data-promo-body>
                        <?php foreach ($promos as $promo):
                            $status = promoStatus($promo, $promoToday); ?>
                            <tr class="catalog-row promo-row" data-promo-status="<?= $status ?>"
                                data-id="<?= h($promo['id']) ?>" data-barcode="<?= h($promo['barcode']) ?>"
                                data-label="<?= h($promo['label']) ?>" data-type="<?= h($promo['type']) ?>"
                                data-value="<?= h($promo['value']) ?>" data-buy="<?= h($promo['buy']) ?>"
                                data-take="<?= h($promo['take']) ?>" data-start="<?= h($promo['start']) ?>"
                                data-end="<?= h($promo['end']) ?>">
                                <td class="cell-product"><span class="product-name" data-promo-name>&nbsp;</span><span class="product-barcode"><?= h($promo['barcode']) ?></span></td>
                                <td class="cell-deal"><span class="promo-tag <?= $promo['label'] === 'special' ? 'special' : 'promo' ?>"><?= h(promoLabelText($promo)) ?></span>
                                    <span class="promo-deal"><?= h(promoDealText($promo)) ?></span></td>
                                <td class="cell-price" data-promo-price></td>
                                <td class="cell-dates"><?= h(promoDateLabel($promo['start'])) ?><?= $promo['start'] !== '' || $promo['end'] !== '' ? ' &ndash; ' : '' ?><?= h(promoDateLabel($promo['end'])) ?></td>
                                <td class="cell-status"><span class="status-pill <?= $status ?>" data-promo-status-label><?= ucfirst($status) ?></span></td>
                                <td class="actions-cell">
                                    <a class="icon-btn row-edit" data-edit-promo title="Edit" aria-label="Edit promo for <?= h($promo['barcode']) ?>"
                                       href="?promo_edit=<?= h(rawurlencode($promo['id'])) ?>"><?= icon('edit') ?></a>
                                </td>
                            </tr>
                        <?php endforeach; ?>
                        <tr class="empty-row" data-promo-empty<?= $promos ? ' hidden' : '' ?>><td colspan="6">
                            <div class="empty-state">
                                <span class="empty-icon"><?= icon('tag') ?></span>
                                <strong data-promo-empty-title><?= $promos ? 'No promos match' : 'No promos yet' ?></strong>
                                <span class="subtle" data-promo-empty-text><?= $promos
                                    ? 'Try another search or status.'
                                    : 'Add a promo, or upload a promos CSV below.' ?></span>
                            </div>
                        </td></tr>
                        </tbody>
                    </table>
                </div>

                <div class="promo-footer">
                    <form method="post" enctype="multipart/form-data" class="promo-upload" data-upload
                          data-confirm="Uploading replaces all current promos. Continue?">
                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                        <input type="hidden" name="action" value="upload_promos">
                        <input type="file" name="promos_file" accept=".csv,.txt,text/csv" required>
                        <button class="secondary btn-sm" type="submit">Upload CSV</button>
                    </form>
                    <div class="actions">
                        <?php if (is_file(promosFile())): ?>
                            <a class="button ghost btn-sm" href="promos.csv" download><?= icon('download') ?> Download CSV</a>
                        <?php endif; ?>
                        <?php if ($promoCounts['ended'] > 0): ?>
                            <form method="post" data-confirm="Remove all <?= (int) $promoCounts['ended'] ?> ended promos?">
                                <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                                <input type="hidden" name="action" value="purge_ended_promos">
                                <button class="ghost btn-sm" type="submit">Remove ended</button>
                            </form>
                        <?php endif; ?>
                    </div>
                    <p class="subtle promo-format">CSV columns (any order): <code>barcode, label, type, value, buy, take, start, end</code>.
                        Type is <code>percent</code>, <code>amount</code>, <code>fixed</code> or <code>buy_take</code>;
                        label is <code>promo</code> or <code>special</code>; dates are YYYY-MM-DD.</p>
                </div>
            </section>
        </div><!-- /tab-promos -->

        <!-- ================================= Design ================================= -->
        <?php
        $designGroups = [
            'check' => ['Price check screen', 'Layout, promo style and colours shown after a scan.'],
            'idle' => ['Idle screen', 'The home screen between scans.'],
            'marquee' => ['Scrolling message', 'The message itself is typed in Display media > Scrolling message.'],
            'timing' => ['Idle media timing', 'How long banners and videos stay up before the next one.'],
        ];
        $designFile = designFile();
        $designUpdated = is_file($designFile) ? (filemtime($designFile) ?: 0) : 0;
        ?>
        <div class="tab-panel" id="tab-design" data-tab="design" role="tabpanel">
            <form method="post" class="design-layout" data-design>
                <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                <input type="hidden" name="action" value="save_design">

                <section class="card catalog-card design-card">
                    <div class="catalog-head">
                        <div class="catalog-title">
                            <span class="catalog-icon"><?= icon('palette') ?></span>
                            <div>
                                <h2>Design</h2>
                                <p class="subtle">Applies to every scanner that syncs with this server.
                                    <span data-design-count><?= count($design) ?></span> of <?= count(designFields()) ?> settings managed<?php if ($designUpdated): ?>
                                    &middot; saved <time data-local datetime="<?= h(date('c', $designUpdated)) ?>"><?= h(date('M j, Y g:i A', $designUpdated)) ?></time><?php endif; ?></p>
                            </div>
                        </div>
                    </div>
                    <p class="design-intro">Tick a setting to control it from here. On the next sync, scanners take the ticked values
                        and replace any change made in their Admin screen. Unticked settings stay as each scanner has them.</p>

                    <?php foreach ($designGroups as $groupKey => [$groupTitle, $groupHint]): ?>
                        <fieldset class="design-group">
                            <legend><?= h($groupTitle) ?></legend>
                            <p class="subtle"><?= h($groupHint) ?></p>
                            <?php foreach (designFields() as $key => $field):
                                if ($field['group'] !== $groupKey) { continue; }
                                $managed = array_key_exists($key, $design);
                                $value = $managed ? $design[$key] : $field['default'];
                                $id = 'design-' . str_replace('_', '-', $key); ?>
                                <div class="design-row<?= $field['type'] === 'choice' ? ' design-row-choice' : '' ?>" data-design-row="<?= h($key) ?>" data-default="<?= h(is_bool($field['default']) ? ($field['default'] ? '1' : '0') : (string) $field['default']) ?>"
                                     data-managed="<?= $managed ? '1' : '0' ?>">
                                    <label class="design-manage">
                                        <input type="checkbox" name="manage[<?= h($key) ?>]" value="1"<?= $managed ? ' checked' : '' ?> data-design-manage>
                                        <span><?= h($field['label']) ?></span>
                                    </label>
                                    <div class="design-control">
                                        <?php if ($field['type'] === 'choice'): ?>
                                            <div class="choice-tiles" role="radiogroup" aria-label="<?= h($field['label']) ?>">
                                                <?php foreach ($field['options'] as $optionKey => $optionLabel): ?>
                                                    <label class="choice-tile">
                                                        <input type="radio" name="value[<?= h($key) ?>]" value="<?= h($optionKey) ?>"<?= $value === $optionKey ? ' checked' : '' ?> data-design-input>
                                                        <span class="choice-thumb" data-thumb="<?= h($key . ':' . $optionKey) ?>" aria-hidden="true"><span class="t-info"><i class="t-name"></i><i class="t-meta"></i></span><span class="t-card"><i class="t-price"></i></span><i class="t-deco"></i></span>
                                                        <span class="choice-name"><?= h($optionLabel) ?></span>
                                                    </label>
                                                <?php endforeach; ?>
                                            </div>
                                        <?php elseif ($field['type'] === 'color'): ?>
                                            <input type="color" value="<?= h((string) $value) ?>" data-color-picker aria-label="<?= h($field['label']) ?> colour">
                                            <input id="<?= h($id) ?>" type="text" name="value[<?= h($key) ?>]" value="<?= h((string) $value) ?>"
                                                   maxlength="7" spellcheck="false" autocomplete="off" class="color-text" data-design-input data-color-text>
                                        <?php elseif ($field['type'] === 'range'): ?>
                                            <input id="<?= h($id) ?>" type="range" name="value[<?= h($key) ?>]" min="<?= (int) $field['min'] ?>" max="<?= (int) $field['max'] ?>"
                                                   step="<?= (int) $field['step'] ?>" value="<?= (int) $value ?>" data-design-input>
                                            <output class="range-value" data-range-value><?= (int) $value ?>%</output>
                                        <?php elseif ($field['type'] === 'bool'): ?>
                                            <label class="switch">
                                                <input id="<?= h($id) ?>" type="checkbox" name="value[<?= h($key) ?>]" value="1"<?= $value ? ' checked' : '' ?> data-design-input>
                                                <span class="switch-track" aria-hidden="true"></span>
                                            </label>
                                        <?php elseif ($field['type'] === 'int'): ?>
                                            <input id="<?= h($id) ?>" type="number" name="value[<?= h($key) ?>]" min="<?= (int) $field['min'] ?>" max="<?= (int) $field['max'] ?>"
                                                   step="1" value="<?= (int) $value ?>" class="number-short" data-design-input>
                                            <span class="subtle"><?= h($field['unit']) ?></span>
                                        <?php else: ?>
                                            <input id="<?= h($id) ?>" type="text" name="value[<?= h($key) ?>]" value="<?= h((string) $value) ?>"
                                                   maxlength="60" placeholder="Leave blank for no name" data-design-input>
                                        <?php endif; ?>
                                    </div>
                                </div>
                            <?php endforeach; ?>
                        </fieldset>
                    <?php endforeach; ?>

                    <div class="design-footer">
                        <button class="primary" type="submit">Save design</button>
                        <button class="secondary js-only" type="button" data-design-defaults>Reset to app defaults</button>
                        <button class="ghost js-only" type="button" data-design-release>Untick all</button>
                    </div>
                </section>

                <aside class="design-preview" aria-label="Preview">
                    <!-- Drawn by app.js in the chosen layout and promo style, the way the app draws them. -->
                    <p class="preview-label">Price check</p>
                    <div class="mock-screen mock-check" data-mock-check></div>
                    <p class="preview-label">With a promo</p>
                    <div class="mock-screen mock-check" data-mock-promo></div>

                    <p class="preview-label">Idle screen</p>
                    <div class="mock-screen mock-idle">
                        <div class="mock-idle-body">
                            <div class="mock-idle-side">
                                <span class="mock-logo" data-mock-logo><?= icon('tag') ?></span>
                                <span class="mock-month">SEPTEMBER</span>
                                <span class="mock-day">29</span>
                                <span class="mock-title">Price Verifier</span>
                            </div>
                            <div class="mock-banner">
                                <span class="mock-scan" data-mock-scan>&darr; SCAN HERE &darr;</span>
                            </div>
                        </div>
                        <div class="mock-marquee" data-mock-marquee>WELCOME! FIND EVERYTHING YOU NEED IN ONE PLACE.</div>
                    </div>

                    <p class="preview-label">Over a video</p>
                    <div class="mock-screen mock-video">
                        <span class="mock-store" data-mock-store>Store name</span>
                        <span class="mock-store-logo" data-mock-store-logo hidden><?= icon('tag') ?></span>
                        <span class="mock-timing" data-mock-timing></span>
                    </div>
                    <p class="subtle preview-note">Unticked settings preview the app's defaults; each scanner may differ.</p>
                </aside>
            </form>
        </div><!-- /tab-design -->

        <!-- ============================== App update ================================ -->
        <!-- Device registration is not managed here: authorized scanner serials are
             hard-coded in the Android app (DeviceAccess.ALLOWED_SERIALS). -->
        <div class="tab-panel" id="tab-devices" data-tab="devices" role="tabpanel">
            <div class="split">
                <section class="card">
                    <div class="card-head">
                        <div>
                            <h2>App update details</h2>
                            <p class="subtle">Shown inside the Android app when a new APK is available.</p>
                        </div>
                        <span class="tag">Update notes</span>
                    </div>
                    <form method="post" autocomplete="off">
                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                        <input type="hidden" name="action" value="save_app_update_info">
                        <label for="app-version">Version</label>
                        <input id="app-version" name="app_version" type="text" spellcheck="false"
                               value="<?= h($appUpdateInfo['version']) ?>" placeholder="1.0.1">
                        <label for="app-changes">Changes</label>
                        <textarea id="app-changes" name="app_changes"
                                  placeholder="Added new feature&#10;Fixed update popup"><?= h($appUpdateInfo['changes']) ?></textarea>
                        <div class="actions" style="margin-top:14px">
                            <button class="primary" type="submit">Save update details</button>
                        </div>
                    </form>
                </section>
            </div>
        </div><!-- /tab-devices -->

        <div class="tab-panel" id="tab-scanners" data-tab="scanners" role="tabpanel">
            <section class="card">
                <div class="card-head">
                    <div>
                        <h2>Sync scanners now</h2>
                        <p class="subtle">Scanners check in with this server every few seconds. Those that are on and connected start
                            at once, in the background; the rest catch up when they reconnect or at their daily sync.
                            Only files that changed are downloaded.</p>
                    </div>
                    <span class="tag">Force sync</span>
                </div>
                <div class="sync-choices">
                    <?php foreach ([
                        'items' => ['primary', 'Catalog, promos and design.'],
                        'images' => ['secondary', 'Logo, banner, scheduled images and the scrolling message.'],
                        'videos' => ['secondary', 'The idle video and scheduled videos.'],
                        'app' => ['secondary', 'Scanners look for a new app version here and show the update prompt. Someone at the scanner taps Download to install it.'],
                        'all' => ['secondary', 'Items, images and videos together.'],
                    ] as $scope => [$buttonClass, $covers]): ?>
                    <form method="post" class="sync-choice">
                        <input type="hidden" name="csrf" value="<?= h(csrfToken()) ?>">
                        <input type="hidden" name="action" value="request_sync">
                        <input type="hidden" name="scope" value="<?= h($scope) ?>">
                        <button class="<?= h($buttonClass) ?>" type="submit"><?= h(DEVICE_SYNC_SCOPES[$scope]) ?></button>
                        <p class="subtle"><?= h($covers) ?></p>
                    </form>
                    <?php endforeach; ?>
                </div>
                <p class="subtle" style="margin-top:10px">Sync images, Sync videos and App update need the newest scanner app;
                    an older app is marked "Needs app update" in the list below.</p>
            </section>
            <section class="card" data-scanner-status>
                <?= renderScannerStatus(scannerStatus()) ?>
            </section>
        </div><!-- /tab-scanners -->
    <?php endif; ?>
</main>
<script>
    window.PV_CONFIG = {
        forcedTab: <?= json_encode($forcedTab) ?>,
        endpoint: <?= json_encode(basename((string) ($_SERVER['SCRIPT_NAME'] ?? 'index.php'))) ?>,
        icons: <?= json_encode([
            'edit' => icon('edit'),
            'search' => icon('search'),
            'box' => icon('box'),
            'prev' => icon('chevron-left'),
            'next' => icon('chevron'),
        ], JSON_UNESCAPED_SLASHES) ?>
    };
</script>
<script src="<?= h(asset('assets/app.js')) ?>" defer></script>
</body>
</html>
