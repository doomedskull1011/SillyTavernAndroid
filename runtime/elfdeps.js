// Prints DT_NEEDED / DT_SONAME of an ELF64 binary (any endianness handled as LE, aarch64/x86_64).
const fs = require('fs');
const f = fs.readFileSync(process.argv[2]);
if (f.readUInt32BE(0) !== 0x7f454c46) { console.error('not ELF'); process.exit(1); }
const is64 = f[4] === 2;
const little = f[5] === 1;
const ru16 = o => little ? f.readUInt16LE(o) : f.readUInt16BE(o);
const ru32 = o => little ? f.readUInt32LE(o) : f.readUInt32BE(o);
const ru64 = o => little ? f.readBigUInt64LE(o) : f.readBigUInt64BE(o);

let phOff, phEntSize, phNum;
if (is64) { phOff = Number(ru64(0x20)); phEntSize = ru16(0x36); phNum = ru16(0x38); }
else { phOff = ru32(0x1c); phEntSize = ru16(0x2a); phNum = ru16(0x2c); }

const loads = [];
let dynOff = 0, dynSize = 0;
for (let i = 0; i < phNum; i++) {
    const o = phOff + i * phEntSize;
    const type = ru32(o);
    if (is64) {
        const off = ru64(o + 8), va = ru64(o + 16), fsz = ru64(o + 32);
        if (type === 2) { dynOff = Number(off); dynSize = Number(fsz); }
        if (type === 1) loads.push({ va, off: Number(off), fsz: Number(fsz) });
    }
}
const v2o = va => { for (const l of loads) { if (va >= l.va && va < l.va + BigInt(l.fsz)) return l.off + Number(va - l.va); } return -1; };

let strtab = -1;
const dyns = [];
for (let o = dynOff; o < dynOff + dynSize; o += 16) {
    const tag = ru64(o), val = ru64(o + 8);
    dyns.push({ tag, val });
    if (tag === 5n) strtab = Number(val);
    if (tag === 0n) break;
}
const strOff = v2o(BigInt(strtab));
const cstr = o => { let e = o; while (f[e] !== 0) e++; return f.slice(o, e).toString('utf8'); };
for (const d of dyns) {
    if (d.tag === 1n) console.log('NEEDED  ', cstr(strOff + Number(d.val)));
    if (d.tag === 14n) console.log('SONAME  ', cstr(strOff + Number(d.val)));
}
