// Patch embedded DT_SONAME strings in the renamed libs so SONAME == filename == DT_NEEDED.
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
const PATCHES = {
    'libz.so': [['libz.so.1', 'libz.so']],
    'libcrypto.so': [['libcrypto.so.3', 'libcrypto.so']],
    'libssl.so': [['libssl.so.3', 'libssl.so']],
    'libicudata.so': [['libicudata.so.78', 'libicudata.so']],
    'libicuuc.so': [['libicuuc.so.78', 'libicuuc.so']],
    'libicui18n.so': [['libicui18n.so.78', 'libicui18n.so']],
};

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

for (const [file, pairs] of Object.entries(PATCHES)) {
    const p = path.join(dir, file);
    const buf = fs.readFileSync(p);
    for (const [oldName, newName] of pairs) {
        const n = patchAll(buf, oldName, newName);
        console.log(`${file}: "${oldName}" -> "${newName}" (${n})`);
    }
    fs.writeFileSync(p, buf);
}
console.log('DONE');
