// Validate the Java-generated script, including its SQL modal behavior.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(process.argv[2], 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
assert.equal(scripts.length, 1);
const code = new vm.Script(scripts[0][1], { filename: 'dashboard-inline.js' });
const elements = new Map();
const listeners = new Map();
const document = {
  addEventListener(name, listener) { listeners.set(name, listener); },
  getElementById(id) {
    if (!elements.has(id)) elements.set(id, {
      textContent: '', open: false, addEventListener() {},
      showModal() { this.open = true; }, close() { this.open = false; },
    });
    return elements.get(id);
  },
};
const context = vm.createContext({ document, fetch: () => new Promise(() => {}), setInterval() {} });
code.runInContext(context);
const format = context.formatSql;
assert.equal(format('select a,b from sample where id = ? order by a'),
  'select a,\n  b\nfrom sample\nwhere id = ?\norder by a');
assert.equal(format('select\ta from\nsample group\tby a'), 'select a\nfrom sample\ngroup\tby a');
assert.equal(format('select selectivity from sample'), 'select selectivity\nfrom sample');
assert.equal(format("select 'from,where',count(*) from sample"),
  "select 'from,where',\n  count(*)\nfrom sample");
assert.equal(format('select "from",`where`,[order by] from sample'),
  'select "from",\n  `where`,\n  [order by]\nfrom sample');
const sql = 'select a,b from sample where id = ?';
listeners.get('click')({ target: { closest: () => ({ textContent: sql }) } });
assert.equal(elements.get('sqlModal').open, true);
assert.equal(elements.get('sqlModalText').textContent, format(sql));
assert.equal(context.escapeHtml('<script>&'), '&lt;script&gt;&amp;');
console.log('PASS: generated JavaScript syntax, SQL whitespace/quotes/keywords and modal click');
