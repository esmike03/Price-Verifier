<?php
declare(strict_types=1);

namespace PriceVerifierStatusTests;

// Exercise the actual portal functions without starting sessions or modifying
// real scanner state. A fixed clock makes expiry-boundary checks deterministic.
const DEVICE_ONLINE_SECONDS = 150;
const DEVICE_SYNC_BASIC_SCOPES = ['items', 'all'];

function time(): int
{
    return 1700000000;
}

function deviceSyncState(): array
{
    return $GLOBALS['scannerFixture'];
}

function extractFunction(string $source, string $name): string
{
    $tokens = token_get_all($source);
    foreach ($tokens as $index => $token) {
        if (!is_array($token) || $token[0] !== T_FUNCTION) {
            continue;
        }
        $next = $index + 1;
        while (isset($tokens[$next]) && is_array($tokens[$next]) && $tokens[$next][0] === T_WHITESPACE) {
            $next++;
        }
        if (!isset($tokens[$next]) || !is_array($tokens[$next]) || $tokens[$next][1] !== $name) {
            continue;
        }
        $code = '';
        $depth = 0;
        $started = false;
        for ($i = $index; $i < count($tokens); $i++) {
            $part = $tokens[$i];
            $code .= is_array($part) ? $part[1] : $part;
            if ($part === '{') {
                $started = true;
                $depth++;
            } elseif ($part === '}' && --$depth === 0 && $started) {
                return $code;
            }
        }
    }
    throw new \RuntimeException('Function missing: ' . $name);
}

$source = file_get_contents(__DIR__ . '/../portal/index.php');
foreach (['deviceOnlineSeconds', 'deviceUnderstandsScope', 'scannerStatus'] as $name) {
    eval('namespace PriceVerifierStatusTests; ' . extractFunction($source, $name));
}

function checkDevice(string $label, int $every, int $age, bool $online, string $sync = 'none', ?array $request = null, array $extra = []): void
{
    $GLOBALS['scannerFixture'] = [
        'request' => $request,
        'devices' => ['TEST-SCANNER' => $extra + [
            'seen' => time() - $age, 'every' => $every, 'app' => 'v6', 'ip' => '192.168.1.50',
        ]],
    ];
    $status = scannerStatus();
    $device = $status['devices'][0];
    if ($device['online'] !== $online || $device['sync'] !== $sync || $status['online'] !== (int) $online) {
        throw new \RuntimeException($label . ': incorrect scanner connection/request status');
    }
    echo "PASS: $label\n";
}

checkDevice('fresh check-in', 2, 0, true);
checkDevice('brief missed check-ins stay online', 2, 5, true);
checkDevice('disconnect expires at six seconds', 2, 6, false);
checkDevice('continued disconnect stays offline', 2, 20, false);
checkDevice('reconnection restores online status', 2, 0, true);
checkDevice('five-second app retains its grace period', 5, 14, true);
checkDevice('five-second app expires at fifteen seconds', 5, 15, false);
checkDevice('legacy app remains online between check-ins', 0, 149, true);
checkDevice('legacy app eventually expires', 0, 150, false);
$request = ['id' => 42, 'scope' => 'items', 'at' => time() - 10];
checkDevice('connected scanner waits for request', 2, 0, true, 'pending', $request);
checkDevice('disconnected scanner request is not reached', 2, 6, false, 'offline', $request);
checkDevice('completed sync does not imply current connectivity', 2, 6, false, 'done', $request, [
    'done' => 42, 'done_at' => time() - 7, 'ok' => true, 'message' => 'Synced',
]);
echo "All scanner status checks passed.\n";
