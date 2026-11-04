Installer tests (Windows) - not part of the package sent to clients
====================================================================

Scanner status checks (no running server or real device required):
  From the project root:
    php server-installer/tests/scanner-status.php
    node server-installer/tests/scanner-status-ui.cjs
  Check offline expiry, reconnection, legacy intervals, request status and
  browser polling without changing actual portal data.

These scripts run the real install.ps1 from an extracted package WITHOUT
administrator rights and without touching C:\PriceVerifier, so a change to the
installer can be checked before it goes to a client.

What is real: copying the files, writing the configuration, the supervisor
(run-server.ps1), PHP, Caddy, pos-sync (Ororama), the health checks and the
diagnostics.

What is replaced by stand-ins (these need administrator rights):
  - Task Scheduler (register / start / stop / disable / enable the task)
  - Windows Firewall rules
  - icacls (file permissions)
  - the "run as administrator" check and the PHP worker ports (moved so they
    do not collide with a server already installed on this PC)
So the real scheduled task, start at boot, the firewall rule and the Visual C++
runtime setup are NOT covered. Check those once on a real PC, as administrator.

How to run
----------
1. Copy this folder to a SHORT path, for example C:\pvit  (the Ororama package
   has long file names; a deep folder goes over Windows' 260-character limit).
2. Extract a package from ..\dist into it, for example C:\pvit\pkg\...
3. In PowerShell:

   Fresh install:
     .\run.ps1 -Package 'pkg\PriceVerifierServer-Windows-1.0.0' -Name a -Port 8098 -FirstPhpPort 9121

   Upgrade over the running server, at the pace of a person answering the
   questions, with the task's watchdog trying to restart the server every
   2 seconds (this is what broke the upgrade on 2026-10-01):
     .\run.ps1 -Package 'pkg\PriceVerifierServer-Windows-1.0.0' -Name a -Port 8098 -FirstPhpPort 9121 -Watchdog -AnswerDelaySeconds 8

   Ororama (pos-sync), fresh install:
     .\run.ps1 -Package 'pkg\PriceVerifierServer-Ororama-Windows-1.0.0' -Name o -Port 8094 -FirstPhpPort 9151 -ExtraArgs "@{ PosSyncPort = 8791; PosSyncConfig = 'C:\pvit\pos-test-config.json' }"
   pos-test-config.json holds made-up MySQL details, so "cannot reach the POS
   database" is the expected result there. Never use the real config.json.

   A file that is briefly locked during the upgrade (antivirus):
     start hold-file.ps1 on a file in <install>\bin\php, then run the upgrade.

   PHP unable to start: copy the package, replace files\bin\php\php-cgi.exe
   with any small program that exits at once, and install from that copy. The
   installer must stop after about 3 minutes, print what the server recorded,
   and end with exit code 1.

Each run prints the installer's output, its exit code, the watchdog's log, the
number of PHP files installed (82), and what the server answers for the portal
page (200) and for a file that does not exist (404).

When finished, stop the test servers (close the hidden PowerShell, caddy,
php-cgi and node processes started from the test folder) and delete the folder.
