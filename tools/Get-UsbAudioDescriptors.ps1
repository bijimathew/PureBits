<#
.SYNOPSIS
Reads standard USB descriptors through Windows' existing USB hub driver.
.DESCRIPTION
Read-only diagnostics: sends only hub GET_NODE_INFORMATION,
GET_NODE_CONNECTION_INFORMATION_EX and GET_DESCRIPTOR requests. Does not play
audio, set volume/EQ, send vendor/class writes, reset ports, or replace drivers.
No device serial-number strings or unrelated device identities are saved.
Microsoft API references:
https://learn.microsoft.com/windows-hardware/drivers/ddi/usbioctl/ni-usbioctl-ioctl_usb_get_descriptor_from_node_connection
https://learn.microsoft.com/windows-hardware/drivers/ddi/usbioctl/ns-usbioctl-_usb_descriptor_request
https://github.com/microsoft/Windows-driver-samples/tree/main/usb/usbview
.EXAMPLE
./tools/Get-UsbAudioDescriptors.ps1 -VendorId 0x31B2 -ProductId 0x1112
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][UInt16]$VendorId,
    [Parameter(Mandatory = $true)][UInt16]$ProductId,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../build/usb-diagnostics')
)
$ErrorActionPreference = 'Stop'

if (-not ('PureBits.UsbDescriptorReader' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace PureBits {
public sealed class UsbDescriptorCapture {
    public byte[] Device;
    public byte[][] Configurations;
    public byte Speed;
    public byte ActiveConfiguration;
}

public static class UsbDescriptorReader {
    private static readonly Guid HubGuid = new Guid("f18a0e88-c30c-11d0-8815-00a0c906bed8");
    // CTL_CODE(FILE_DEVICE_USB=0x22, function, METHOD_BUFFERED, FILE_ANY_ACCESS).
    private const uint GetHubInformation = 0x220408;
    private const uint GetConnectionInformation = 0x220448;
    private const uint GetDescriptor = 0x220410;
    [StructLayout(LayoutKind.Sequential)]
    private struct InterfaceData {
        public uint Size;
        public Guid Guid;
        public uint Flags;
        public IntPtr Reserved;
    }
    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr SetupDiGetClassDevs(ref Guid guid, IntPtr enumerator, IntPtr parent, uint flags);
    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiEnumDeviceInterfaces(IntPtr set, IntPtr device, ref Guid guid, uint index, ref InterfaceData data);
    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr set, ref InterfaceData data, IntPtr detail, uint size, out uint needed, IntPtr device);
    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateFile(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool DeviceIoControl(SafeFileHandle handle, uint code, byte[] input, uint inputSize, byte[] output, uint outputSize, out uint returned, IntPtr overlapped);

    private static IEnumerable<string> Hubs() {
        Guid guid = HubGuid;
        IntPtr set = SetupDiGetClassDevs(ref guid, IntPtr.Zero, IntPtr.Zero, 0x12);
        if (set == new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error());
        try {
            for (uint i = 0; ; ++i) {
                InterfaceData data = new InterfaceData();
                data.Size = (uint)Marshal.SizeOf(typeof(InterfaceData));
                if (!SetupDiEnumDeviceInterfaces(set, IntPtr.Zero, ref guid, i, ref data)) {
                    if (Marshal.GetLastWin32Error() == 259) break;
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                uint needed;
                SetupDiGetDeviceInterfaceDetail(set, ref data, IntPtr.Zero, 0, out needed, IntPtr.Zero);
                IntPtr detail = Marshal.AllocHGlobal((int)needed);
                try {
                    Marshal.WriteInt32(detail, IntPtr.Size == 8 ? 8 : 6);
                    if (!SetupDiGetDeviceInterfaceDetail(set, ref data, detail, needed, out needed, IntPtr.Zero))
                        throw new Win32Exception(Marshal.GetLastWin32Error());
                    yield return Marshal.PtrToStringUni(IntPtr.Add(detail, 4));
                } finally { Marshal.FreeHGlobal(detail); }
            }
        } finally { SetupDiDestroyDeviceInfoList(set); }
    }

    private static bool Query(SafeFileHandle handle, uint code, byte[] data, out uint returned) {
        // The only allowed requests are the three documented, read-only hub IOCTLs.
        if (code != GetHubInformation && code != GetConnectionInformation && code != GetDescriptor)
            throw new InvalidOperationException("Non-read USB request refused");
        return DeviceIoControl(handle, code, data, (uint)data.Length, data, (uint)data.Length, out returned, IntPtr.Zero);
    }

    private static byte[] Configuration(SafeFileHandle hub, uint port, int index) {
        // USB_DESCRIPTOR_REQUEST is packed: ULONG port + 8-byte setup + data.
        byte[] request = new byte[12 + 65535];
        Array.Copy(BitConverter.GetBytes(port), request, 4);
        request[4] = 0x80; // Standard device-to-host; hub also enforces this.
        request[5] = 0x06; // GET_DESCRIPTOR; never SET_INTERFACE/SET_CUR.
        request[6] = (byte)index;
        request[7] = 0x02; // Configuration descriptor type.
        request[10] = 0xff;
        request[11] = 0xff;
        uint returned;
        if (!Query(hub, GetDescriptor, request, out returned)) throw new Win32Exception(Marshal.GetLastWin32Error());
        if (returned < 21 || request[12] != 9 || request[13] != 2) throw new InvalidOperationException("Malformed configuration response");
        int total = BitConverter.ToUInt16(request, 14);
        if (total < 9 || total > returned - 12) throw new InvalidOperationException("Truncated configuration descriptor");
        byte[] result = new byte[total];
        Array.Copy(request, 12, result, 0, total);
        return result;
    }

    public static UsbDescriptorCapture Capture(ushort vendor, ushort product) {
        foreach (string path in Hubs()) {
            // Zero desired access suffices for FILE_ANY_ACCESS queries on supported
            // hubs. No read/write data handle is opened on the audio function.
            using (SafeFileHandle hub = CreateFile(path, 0, 3, IntPtr.Zero, 3, 0, IntPtr.Zero)) {
                if (hub.IsInvalid) continue;
                uint returned;
                byte[] info = new byte[80];
                if (!Query(hub, GetHubInformation, info, out returned) || returned < 7) continue;
                int ports = info[6];
                for (uint port = 1; port <= ports; ++port) {
                    byte[] connection = new byte[4096];
                    Array.Copy(BitConverter.GetBytes(port), connection, 4);
                    if (!Query(hub, GetConnectionInformation, connection, out returned) || returned < 35) continue;
                    if (connection[4] != 18 || connection[5] != 1) continue;
                    if (BitConverter.ToUInt16(connection, 12) != vendor || BitConverter.ToUInt16(connection, 14) != product) continue;
                    UsbDescriptorCapture result = new UsbDescriptorCapture();
                    result.Device = new byte[18];
                    Array.Copy(connection, 4, result.Device, 0, 18);
                    result.ActiveConfiguration = connection[22];
                    result.Speed = connection[23];
                    int count = result.Device[17];
                    if (count < 1) throw new InvalidOperationException("Device has no configurations");
                    result.Configurations = new byte[count][];
                    for (int index = 0; index < count; ++index) result.Configurations[index] = Configuration(hub, port, index);
                    return result;
                }
            }
        }
        throw new InvalidOperationException("Requested USB VID/PID was not found on an accessible hub");
    }
}
}
'@
}

$capture = [PureBits.UsbDescriptorReader]::Capture($VendorId, $ProductId)
$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $resolvedOutput -Force
$prefix = 'usb-{0:X4}-{1:X4}' -f $VendorId, $ProductId
[IO.File]::WriteAllBytes((Join-Path $resolvedOutput "$prefix-device.bin"), $capture.Device)
$manifest = [ordered]@{
    capturedUtc = [DateTime]::UtcNow.ToString('o')
    platform = 'Windows USB hub standard descriptor queries (no playback or device writes)'
    vendorId = ('0x{0:X4}' -f $VendorId)
    productId = ('0x{0:X4}' -f $ProductId)
    bcdDevice = ('0x{0:X4}' -f [BitConverter]::ToUInt16($capture.Device, 12))
    connectionSpeedCode = $capture.Speed
    activeConfiguration = $capture.ActiveConfiguration
    configurationFiles = @()
}
for ($index = 0; $index -lt $capture.Configurations.Length; $index++) {
    $name = "$prefix-config-$index.bin"
    $data = $capture.Configurations[$index]
    [IO.File]::WriteAllBytes((Join-Path $resolvedOutput $name), $data)
    $rawName = "$prefix-android-style-$index.bin"
    [IO.File]::WriteAllBytes((Join-Path $resolvedOutput $rawName), [byte[]]($capture.Device + $data))
    $hex = for ($at = 0; $at -lt $data.Length; $at += 16) {
        $end = [Math]::Min($at + 15, $data.Length - 1)
        '{0:X4}: {1}' -f $at, (($data[$at..$end] | ForEach-Object { '{0:X2}' -f $_ }) -join ' ')
    }
    $hex | Set-Content -LiteralPath (Join-Path $resolvedOutput "$prefix-config-$index.hex.txt") -Encoding UTF8
    $manifest.configurationFiles += [ordered]@{ file = $name; bytes = $data.Length; androidStyleFile = $rawName }
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $resolvedOutput "$prefix-summary.json") -Encoding UTF8
$manifest | ConvertTo-Json -Depth 5
Write-Output "Saved descriptor artifacts to $resolvedOutput"
