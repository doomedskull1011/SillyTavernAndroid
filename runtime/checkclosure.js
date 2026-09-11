// Verifies every bundled .so's DT_NEEDED is satisfiable by the bundle + Android system libs,
// and prints PT_INTERP of executables.
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
const SYSTEM_LIBS = new Set(['libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
    'libz.so', 'ld-android.so', 'libnativeloader.so', 'libnetd_client.so']);

function parseElf(f) {
    if (f.readUInt32BE(0) !== 0x7f454c46) return null;
    const is64 = f[4] === 2, little = f[5] === 1;
    const ru16 = o => little ? f.readUInt16LE(o) : f.readUInt16BE(o);
    const ru32 = o => little ? f.readUInt32LE(o) : f.readUInt32BE(o);
    const ru64 = o => little ? f.readBigUInt64LE(o) : f.readBigUInt64BE(o);
    let phOff, phEnt, phNum;
    if (is64) { phOff = Number(ru64(0x20)); phEnt = ru16(0x36); phNum = ru16(0x38); }
    else { phOff = ru32(0x1c); phEnt = ru16(0x2a); phNum = ru16(0x2c); }
    const loads = []; let dynOff = 0, dynSize = 0, interp = null;
    for (let i = 0; i < phNum; i++) {
        const o = phOff + i * phEnt;
        const type = ru32(o);
        const off = ru64(o + 8), va = ru64(o + 16), fsz = ru64(o + 32);
        if (type === 2) { dynOff = Number(off); dynSize = Number(fsz); }
        if (type === 1) loads.push({ va, off: Number(off), fsz: Number(fsz) });
        if (type === 3) { // PT_INTERP
            const io = Number(off); let e = io; while (f[e] !== 0) e++;
            interp = f.slice(io, e).toString();
        }
    }
    const v2o = v => { for (const l of loads) if (v >= l.va && v < l.va + BigInt(l.fsz)) return l.off + Number(v - l.va); return -1; };
    const needed = [];
    let strtab = -1;
    for (let o = dynOff; dynSize && o < dynOff + dynSize; o += 16) {
        const tag = ru64(o), val = ru64(o + 8);
        if (tag === 0n) break;
        if (tag === 5n) strtab = Number(val);
        if (tag === 1n) needed.push(val);
    }
    const so = strtab >= 0 ? v2o(BigInt(strtab)) : -1;
    const cstr = o => { let e = o; while (f[e] !== 0) e++; return f.slice(o, e).toString('utf8'); };
    return { interp, needed: needed.map(v => cstr(so + Number(v))) };
}

let ok = true;
for (const name of fs.readdirSync(dir)) {
    const f = fs.readFileSync(path.join(dir, name));
    const elf = parseElf(f);
    if (!elf) { console.log(`${name}: NOT ELF`); ok = false; continue; }
    if (elf.interp) console.log(`${name}: INTERP=${elf.interp}`);
    const missing = elf.needed.filter(n => !SYSTEM_LIBS.has(n) && !fs.existsSync(path.join(dir, n)));
    console.log(`${name}: needs [${elf.needed.join(', ')}] ${missing.length ? 'MISSING: ' + missing.join(', ') : 'OK'}`);
    if (missing.length) ok = false;
}
console.log(ok ? '\nCLOSURE OK' : '\nCLOSURE FAILED');
process.exit(ok ? 0 : 1);
