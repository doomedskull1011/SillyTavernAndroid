// Patches DT_NEEDED strings in ELF binaries in-place (new name must be <= old name length),
// then renames the library files so Android Gradle Plugin packages them (must match lib*.so).
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];

// file -> [ [oldName, newName], ... ]  (strings to patch INSIDE that file)
const PATCHES = {
    'libnodeexec.so': [
        ['libz.so.1', 'libz.so'],
        ['libcrypto.so.3', 'libcrypto.so'],
        ['libssl.so.3', 'libssl.so'],
        ['libicui18n.so.78', 'libicui18n.so'],
        ['libicuuc.so.78', 'libicuuc.so'],
    ],
    'libsqlite3.so': [['libz.so.1', 'libz.so']],
    'libssl.so.3': [['libcrypto.so.3', 'libcrypto.so']],
    'libicui18n.so.78': [['libicuuc.so.78', 'libicuuc.so']],
    'libicuuc.so.78': [['libicudata.so.78', 'libicudata.so']],
};

// file renames (after patching)
const RENAMES = {
    'libz.so.1': 'libz.so',
    'libcrypto.so.3': 'libcrypto.so',
    'libssl.so.3': 'libssl.so',
    'libicui18n.so.78': 'libicui18n.so',
    'libicuuc.so.78': 'libicuuc.so',
    'libicudata.so.78': 'libicudata.so',
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
        if (newName.length > oldName.length) throw new Error('new name longer!');
        const n = patchAll(buf, oldName, newName);
        if (n === 0) throw new Error(`string "${oldName}" not found in ${file}`);
        console.log(`${file}: "${oldName}" -> "${newName}" (${n} occurrence${n > 1 ? 's' : ''})`);
    }
    fs.writeFileSync(p, buf);
}

for (const [from, to] of Object.entries(RENAMES)) {
    fs.renameSync(path.join(dir, from), path.join(dir, to));
    console.log(`renamed ${from} -> ${to}`);
}
console.log('DONE');
