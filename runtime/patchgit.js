// Patches versioned DT_NEEDED strings (libssl.so.3 / libcrypto.so.3 / libz.so.1)
// in the git runtime binaries to the unversioned names we ship in jniLibs.
// Tolerant: a string that is absent is skipped (unlike patchdeps.js which throws).
// Usage: node patchgit.js <dir-containing-files-to-patch-recursively>
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
const PAIRS = [
    ['libcrypto.so.3', 'libcrypto.so'],
    ['libssl.so.3', 'libssl.so'],
    ['libz.so.1', 'libz.so'],
];

function patchAll(buf, oldName, newName) {
    const oldBytes = Buffer.from(oldName + '\0', 'utf8');
    const newBytes = Buffer.alloc(oldBytes.length, 0);
    Buffer.from(newName, 'utf8').copy(newBytes);
    let count = 0, idx = 0;
    while ((idx = buf.indexOf(oldBytes, idx)) !== -1) {
        newBytes.copy(buf, idx);
        count++;
        idx += oldBytes.length;
    }
    return count;
}

function* walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, e.name);
        if (e.isDirectory()) yield* walk(p);
        else yield p;
    }
}

for (const p of walk(dir)) {
    const buf = fs.readFileSync(p);
    if (buf.length < 4 || buf.readUInt32BE(0) !== 0x7f454c46) continue; // ELF only
    let changed = false;
    for (const [oldName, newName] of PAIRS) {
        const n = patchAll(buf, oldName, newName);
        if (n > 0) {
            console.log(`${path.basename(p)}: "${oldName}" -> "${newName}" (${n}x)`);
            changed = true;
        }
    }
    if (changed) fs.writeFileSync(p, buf);
}
console.log('DONE');
