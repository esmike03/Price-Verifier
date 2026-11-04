Testing the Linux installer on a real Ubuntu (VirtualBox)
=========================================================

Used on 2026-10-08 to test the main and the Ororama Linux packages on Ubuntu
24.04, and to compare them with the Windows packages. Nothing here goes into
the packages sent to clients.

make-vm.ps1          Creates a throwaway Ubuntu VM "pv-ubuntu-test" from the
                     official cloud image (download noble-server-cloudimg-amd64.ova
                     from cloud-images.ubuntu.com into the same folder as
                     noble.ova first, and check its SHA256). Put the folder on a
                     short path with free space, e.g. D:\pv-linux-test.
                     On this PC the VM only boots reliably with 1 CPU and NAT:
                       VBoxManage modifyvm pv-ubuntu-test --cpus 1 --nic1 nat
                       VBoxManage storageattach pv-ubuntu-test --storagectl IDE --port 0 --device 0 --type dvddrive --medium seed.iso
                     Do not use bridged networking: the VM then takes an
                     address on the office network.
sshvm.sh             Runs a command in the VM (key made by make-vm.ps1).
portal-check.sh      36 checks of an installed server done the way staff and
                     scanners use it (sign-in, catalog CSV/Excel upload, add and
                     update, scrolling message, banner, the five force-sync
                     requests, online/offline, pos-sync). Run it against the
                     Windows install and the Linux install and diff the outputs:
                       bash portal-check.sh http://127.0.0.1:8000 http://127.0.0.1:8090 <pos token> <test-files folder>
                     It creates the portal password, so use a fresh install.
test-files\          The catalog, Excel and image files the checks upload.
pos-db.sql           A stand-in POS database (rdmssql.RD5500, 6 rows) for MySQL
                     in the VM, so pos-sync can be tested against a real database.
compare-packages.py  Compares the built packages file by file (portal and
                     pos-sync, Windows vs Linux, Ororama vs main).
result-*.txt         The outputs from 2026-10-08; they were identical.

In the VM, as a client would:
    tar -xzf PriceVerifierServer-...-Linux-1.0.0.tar.gz
    cd PriceVerifierServer-...-Linux-1.0.0
    sudo bash install.sh
For a run without questions:
    sudo QUIET=1 PORT=8000 POS_PORT=8090 IMPORT_FROM= POS_SYNC_CONFIG=/path/config.json bash install.sh

Worth repeating after any installer change: fresh install, upgrade with ufw
enabled, reboot (both services must come back), install with curl removed
(sudo mv /usr/bin/curl /usr/bin/curl.hidden), uninstall with and without data.

When done: VBoxManage controlvm pv-ubuntu-test poweroff, then
VBoxManage unregistervm pv-ubuntu-test --delete, and delete the folder.

Separate installers (Ororama: web app only, pos-sync only)
-----------------------------------------------------------
Tested the same day with the same checks; the result-separate-*.txt files match
the combined results line for line. Scenarios worth repeating after a change:
  - pos-sync only, then web app only, on one machine; upgrade each and check the
    other keeps running and keeps its firewall rule
  - uninstall one (with and without data) while the other keeps working
  - pos-sync installed by typing the answers instead of giving a config.json:
      printf '8090\n\n127.0.0.1\n3306\nuser\npassword\nrdmssql\nRD5500\nIN_CODE\nITE_DESC\nUNT_PRIC\nUNT_CODE\nDEP_CODE\n\n' | script -qec "sudo bash install.sh" /dev/null
  - a machine with the COMBINED package: the web-app-only installer must refuse;
    the pos-sync installer takes over (same port and token); after that the
    web-app-only installer is accepted; a pos-sync install that fails half way
    must leave the built-in pos-sync running
On Windows the same scenarios run without administrator rights with
..\harness2.ps1 and ..\wrun.ps1 (stand-ins for Task Scheduler and the firewall;
-LegacyDir points the pos-sync installer at the test copy of the combined
install instead of C:\PriceVerifier).
