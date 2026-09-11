const c = require('node:crypto');
console.log('crypto ok', c.createHash('sha256').update('x').digest('hex').slice(0, 8));
console.log('icu ok', new Intl.DateTimeFormat('en', { dateStyle: 'full' }).format(new Date(0)));
try {
    const { DatabaseSync } = require('node:sqlite');
    const db = new DatabaseSync('/data/local/tmp/t.db');
    db.exec('CREATE TABLE IF NOT EXISTS t(x)');
    console.log('sqlite ok');
} catch (e) {
    console.log('sqlite FAIL', e.message);
}
const http = require('node:http');
const srv = http.createServer((req, res) => { res.writeHead(200); res.end('hello from node'); });
srv.listen(8123, '127.0.0.1', () => {
    console.log('http ok');
    fetch('http://127.0.0.1:8123').then(r => r.text()).then(t => { console.log('fetch ok:', t); process.exit(0); });
});
setTimeout(() => { console.log('TIMEOUT'); process.exit(1); }, 15000);
