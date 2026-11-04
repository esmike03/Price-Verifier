# Creates a throwaway Ubuntu 24.04 VM in VirtualBox for testing the Linux installer.
# Everything lives in this folder; remove-vm.ps1 deletes it again.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$vb = 'C:\Program Files\Oracle\VirtualBox\VBoxManage.exe'
$vm = 'pv-ubuntu-test'

# A key pair used only for this VM.
if (-not (Test-Path "$here\id_test")) {
    & ssh-keygen.exe -q -t ed25519 -N '""' -C pv-test -f "$here\id_test"
}
$public = (Get-Content "$here\id_test.pub" -Raw).Trim()

# cloud-init seed: lets the "ubuntu" user sign in with that key.
$seed = "$here\seed"
New-Item -ItemType Directory -Force $seed | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText("$seed\meta-data", "instance-id: pv-test-1`nlocal-hostname: pv-ubuntu-test`n", $utf8)
[IO.File]::WriteAllText("$seed\user-data", "#cloud-config`nssh_authorized_keys:`n  - $public`npackage_update: false`n", $utf8)

# An ISO labelled "cidata" is where cloud-init looks for those two files.
$iso = "$here\seed.iso"
if (Test-Path $iso) { Remove-Item $iso -Force }
Add-Type -TypeDefinition @'
using System; using System.IO; using System.Runtime.InteropServices; using System.Runtime.InteropServices.ComTypes;
public static class IsoWriter {
    public static void Save(object stream, string path) {
        IStream source = (IStream)stream;
        using (FileStream target = File.Create(path)) {
            byte[] buffer = new byte[65536];
            IntPtr readPtr = Marshal.AllocHGlobal(sizeof(int));
            try {
                while (true) {
                    source.Read(buffer, buffer.Length, readPtr);
                    int read = Marshal.ReadInt32(readPtr);
                    if (read <= 0) break;
                    target.Write(buffer, 0, read);
                }
            } finally { Marshal.FreeHGlobal(readPtr); }
        }
    }
}
'@
$image = New-Object -ComObject IMAPI2FS.MsftFileSystemImage
$image.FileSystemsToCreate = 3      # ISO9660 + Joliet
$image.VolumeName = 'cidata'
$image.Root.AddTree($seed, $false)
[IsoWriter]::Save($image.CreateResultImage().ImageStream, $iso)
"seed.iso: $((Get-Item $iso).Length) bytes"

# The VM itself: imported from the official cloud image, NAT with forwarded ports.
# VBoxManage writes its progress to the error stream; that must not stop the script.
$ErrorActionPreference = 'Continue'
& $vb import "$here\noble.ova" --vsys 0 --vmname $vm --basefolder "$here\vm" --memory 1536 --cpus 1 | Select-Object -Last 1
# One CPU and NAT: with Hyper-V active on the host the VM hangs at boot with two
# CPUs, and a bridged adapter would put the VM on the office network.
& $vb modifyvm $vm --cpus 1 --nic1 nat --uart1 0x3F8 4 --uartmode1 file "$here\console.log" --audio none --boot1 disk
foreach ($rule in 'ssh,tcp,127.0.0.1,2222,,22', 'portal,tcp,127.0.0.1,18000,,8000', 'pos,tcp,127.0.0.1,18090,,8090', 'mysql,tcp,127.0.0.1,3399,,3306') {
    & $vb modifyvm $vm --natpf1 $rule
}
& $vb storageattach $vm --storagectl IDE --port 0 --device 0 --type dvddrive --medium $iso
& $vb startvm $vm --type headless | Select-Object -Last 1
"Wait about half a minute, then: bash sshvm.sh 'echo up'"
