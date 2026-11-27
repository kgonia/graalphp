# Each logical processor's core, efficiency class (0 = efficient core, higher = performance core) and NUMA node, from
# GetSystemCpuSetInformation: which of the host's 24 CPUs are P-cores.
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class CpuSets {
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetSystemCpuSetInformation(IntPtr info, uint length, out uint returned, IntPtr process, uint flags);
    public static string[] Read() {
        uint len;
        GetSystemCpuSetInformation(IntPtr.Zero, 0, out len, IntPtr.Zero, 0);
        IntPtr buf = Marshal.AllocHGlobal((int) len);
        GetSystemCpuSetInformation(buf, len, out len, IntPtr.Zero, 0);
        System.Collections.Generic.List<string> rows = new System.Collections.Generic.List<string>();
        int off = 0;
        while (off < len) {
            int size = Marshal.ReadInt32(buf, off);
            // SYSTEM_CPU_SET_INFORMATION: Size, Type, then Id(4) Group(2) LogicalProcessorIndex(1) CoreIndex(1)
            // LastLevelCacheIndex(1) NumaNodeIndex(1) EfficiencyClass(1)
            int id = Marshal.ReadInt32(buf, off + 8);
            int lp = Marshal.ReadByte(buf, off + 14);
            int core = Marshal.ReadByte(buf, off + 15);
            int llc = Marshal.ReadByte(buf, off + 16);
            int eff = Marshal.ReadByte(buf, off + 18);
            rows.Add(String.Format("lp {0} core {1} llc {2} efficiency {3}", lp, core, llc, eff));
            off += size;
        }
        Marshal.FreeHGlobal(buf);
        return rows.ToArray();
    }
}
"@
[CpuSets]::Read()
