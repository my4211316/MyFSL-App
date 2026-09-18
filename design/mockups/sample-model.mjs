// 【已停用】這是 v1「所有卡片合併計算」時期的模型，v2.1 起改為逐卡計算，這份的數字已不再正確。
// 設計稿與規格書的數字以 sample-numbers.json（由 DesignNumbersDump 從 App 引擎產生）為準。
// 以 App 計算引擎相同的半月邏輯，替設計稿的示意資料算出一致的數字。
const LIQUID = new Set(['bank', 'cash']);
const CARD = new Set(['cardA', 'cardB']);
const baseAccounts = { bank: 120000, cash: 15000, cardA: 60000, cardB: 45000, loan: 600000, policy: 800000 };
const kind = (id) => (LIQUID.has(id) ? 'liquid' : CARD.has(id) ? 'card' : 'loan');

const M = (o) => { const a = Array(13).fill(0); for (const [m, v] of Object.entries(o)) a[+m] = v; return a; };
const all = (v) => Array(13).fill(v).map((x, i) => (i === 0 ? 0 : x));

const items = [
  { id: 'salary', name: '薪資', group: '收入', type: 'INCOME', acc: 'bank', t: 'F', amt: all(65000) },
  { id: 'bonus', name: '年終獎金', group: '收入', type: 'INCOME', acc: 'bank', t: 'F', amt: M({ 2: 150000 }) },
  { id: 'subsidy', name: '教育補助', group: '收入', type: 'INCOME', acc: 'bank', t: 'F', amt: M({ 3: 5000, 9: 5000 }) },
  { id: 'tax', name: '所得稅', group: '稅費與保險', type: 'EXPENSE', acc: 'bank', t: 'S', amt: M({ 5: 45000 }) },
  { id: 'vehtax', name: '牌照燃料稅', group: '稅費與保險', type: 'EXPENSE', acc: 'bank', t: 'S', amt: M({ 4: 7120, 7: 4800 }) },
  { id: 'ins', name: '保險費', group: '稅費與保險', type: 'EXPENSE', acc: 'bank', t: 'F', amt: M({ 1: 45000, 7: 45000 }) },
  { id: 'elec', name: '電費', group: '生活繳費', type: 'EXPENSE', acc: 'bank', t: 'S', amt: M({ 1: 900, 3: 900, 5: 1200, 7: 1500, 9: 1500, 11: 900 }) },
  { id: 'gas', name: '瓦斯', group: '生活繳費', type: 'EXPENSE', acc: 'bank', t: 'S', amt: M({ 2: 700, 4: 700, 6: 700, 8: 700, 10: 700, 12: 700 }) },
  { id: 'park', name: '停車費', group: '固定支出', type: 'EXPENSE', acc: 'bank', t: 'F', amt: all(2000) },
  { id: 'policyint', name: '保單借款利息', group: '固定支出', type: 'EXPENSE', acc: 'bank', t: 'S', amt: M({ 6: 30000, 12: 30000 }) },
  { id: 'lifecash', name: '生活費現金', group: '生活', type: 'EXPENSE', acc: 'cash', t: 'P', flex: true, amt: all(9000) },
  { id: 'home', name: '家用', group: '生活', type: 'EXPENSE', acc: 'cash', t: 'P', flex: true, amt: all(1000) },
  { id: 'lifecard', name: '生活費信用卡', group: '生活', type: 'EXPENSE', acc: 'cardA', t: 'P', flex: true, amt: all(16000) },
  { id: 'fuel', name: '交通油資', group: '生活', type: 'EXPENSE', acc: 'cardB', t: 'P', flex: true, amt: all(3500) },
  { id: 'phone', name: '手機網路', group: '生活', type: 'EXPENSE', acc: 'cardB', t: 'F', amt: all(2400) },
  { id: 'class', name: '才藝課', group: '小孩活動', type: 'EXPENSE', acc: 'bank', t: 'F', flex: true, amt: all(6000) },
  { id: 'contest', name: '比賽報名', group: '小孩活動', type: 'EXPENSE', acc: 'cash', t: 'S', flex: true, amt: M({ 4: 6000, 10: 6000 }) },
  { id: 'redpkt', name: '紅包', group: '年度', type: 'EXPENSE', acc: 'cash', t: 'F', flex: true, amt: M({ 2: 40000 }) },
  { id: 'bday', name: '生日', group: '年度', type: 'EXPENSE', acc: 'cash', t: 'S', flex: true, amt: M({ 2: 5000, 6: 5000, 11: 5000 }) },
  { id: 'service', name: '汽車保養', group: '年度', type: 'EXPENSE', acc: 'cardB', t: 'S', amt: M({ 3: 12000, 9: 12000 }) },
  { id: 'trip', name: '家族旅遊', group: '年度', type: 'EXPENSE', acc: 'cardA', t: 'S', flex: true, amt: M({ 8: 52180 }) },
  { id: 'payA', name: '繳信用卡 A', group: '繳卡費與貸款', type: 'TRANSFER', acc: 'bank', to: 'cardA', t: 'F', amt: all(18000) },
  { id: 'payB', name: '繳信用卡 B', group: '繳卡費與貸款', type: 'TRANSFER', acc: 'bank', to: 'cardB', t: 'S', amt: all(9000) },
];
const actuals = { lifecash: 4400, home: 300, lifecard: 9800, fuel: 2300 };

const idx = (y, m, h) => y * 24 + (m - 1) * 2 + h;
const fromIdx = (i) => ({ y: Math.floor(i / 24), m: Math.floor((i % 24) / 2) + 1, h: (i % 24) % 2 });
const label = (i) => { const p = fromIdx(i); return `${p.y}年${p.m}月${p.h ? '下' : '上'}`; };
const START = idx(2026, 9, 0);

function schedule(balance, rate, months) {
  const r = rate / 100 / 12;
  const level = Math.round(balance * r / (1 - Math.pow(1 + r, -months)));
  const out = []; let rem = balance;
  for (let n = 1; n <= months; n++) {
    const interest = Math.round(rem * r);
    const principal = n === months ? rem : Math.min(Math.max(level - interest, 0), rem);
    rem -= principal; out.push({ principal, interest });
  }
  return { level, out };
}

function baseline(count) {
  const ev = [];
  const end = START + count;
  for (let i = START; i < end; i += 2) {
    const { y, m } = fromIdx(i);
    for (const it of items) {
      let a = it.amt[m];
      if (y === 2026 && m === 9 && actuals[it.id] != null) a = Math.max(a - actuals[it.id], 0);
      if (!a) continue;
      const parts = it.t === 'F' ? [a, 0] : it.t === 'S' ? [0, a] : [Math.floor(a / 2), a - Math.floor(a / 2)];
      parts.forEach((v, h) => { if (v && i + h < end) ev.push({ i: i + h, kind: it.type, amount: v, from: it.type === 'INCOME' ? null : it.acc, to: it.type === 'INCOME' ? it.acc : it.to || null, item: it.id, flex: !!it.flex, src: 'PLAN' }); });
    }
  }
  const s = schedule(600000, 7.5, 48);
  s.out.forEach((ins, k) => {
    const i = idx(2026, 9, 1) + k * 2;
    if (i >= end) return;
    ev.push({ i, kind: 'TRANSFER', amount: ins.principal, from: 'bank', to: 'loan', rel: 'loan', src: 'LOAN' });
    ev.push({ i, kind: 'EXPENSE', amount: ins.interest, from: 'bank', rel: 'loan', src: 'LOAN' });
  });
  return { accounts: { ...baseAccounts }, events: ev, count, loanLevel: s.level };
}

function run({ accounts, events, count }) {
  const bal = { ...accounts };
  const k = (id) => (id in bal ? (id === 'newloan' ? 'loan' : kind(id)) : null);
  const periods = [];
  for (let i = START; i < START + count; i++) {
    const liquidStart = sum(bal, (id) => k(id) === 'liquid');
    let out = 0, inn = 0, income = 0, expense = 0, principal = 0, cardSpend = 0, cardPay = 0, borrow = 0;
    const list = events.filter((e) => e.i === i).sort((a, b) => (b.full ? 1 : 0) - (a.full ? 1 : 0));
    for (const e of list) {
      const amount = e.full ? Math.max(bal[e.to] || 0, 0) : e.amount;
      if (amount <= 0) continue;
      const take = (id, count_) => { if (!k(id)) return; if (k(id) === 'liquid') { bal[id] -= amount; if (count_) out += amount; } else bal[id] += amount; };
      const put = (id, count_) => { if (!k(id)) return; if (k(id) === 'liquid') { bal[id] += amount; if (count_) inn += amount; } else bal[id] -= amount; };
      if (e.kind === 'INCOME') { put(e.to, true); income += amount; }
      else if (e.kind === 'EXPENSE') { take(e.from, true); expense += amount; if (k(e.from) === 'card') cardSpend += amount; }
      else {
        const internal = k(e.from) === 'liquid' && k(e.to) === 'liquid';
        take(e.from, !internal); put(e.to, !internal);
        if (k(e.from) === 'liquid' && k(e.to) === 'card' && !e.full) cardPay += amount;
        if (k(e.from) === 'liquid' && k(e.to) === 'loan' && !e.full) principal += amount;
        if (k(e.from) === 'loan' && k(e.to) === 'liquid') borrow += amount;
      }
    }
    periods.push({ i, low: liquidStart + borrow - out, end: sum(bal, (id) => k(id) === 'liquid'), card: sum(bal, (id) => k(id) === 'card'), debt: sum(bal, (id) => k(id) !== 'liquid'), income, expense, principal, cardSpend, cardPay });
  }
  return periods;
}
const sum = (o, f) => Object.entries(o).reduce((s, [id, v]) => s + (f(id) ? v : 0), 0);

function integrate(b) {
  const at = idx(2026, 10, 0);
  let ev = b.events.filter((e) => !(e.src !== 'SCEN' && e.i >= at && e.kind === 'TRANSFER' && CARD.has(e.to)));
  ev = ev.map((e) => (e.kind === 'EXPENSE' && CARD.has(e.from) && e.i >= at ? { ...e, from: 'cash' } : e));
  ev.push({ i: at, kind: 'TRANSFER', amount: 200000, from: 'newloan', to: 'bank', src: 'SCEN' });
  ev.push({ i: at, kind: 'TRANSFER', amount: 0, from: 'bank', to: 'cardA', full: true, src: 'SCEN' });
  ev.push({ i: at, kind: 'TRANSFER', amount: 0, from: 'bank', to: 'cardB', full: true, src: 'SCEN' });
  const s = schedule(200000, 6.5, 60);
  s.out.forEach((ins, n) => {
    const i = idx(2026, 11, 1) + n * 2;
    if (i >= START + b.count) return;
    ev.push({ i, kind: 'TRANSFER', amount: ins.principal, from: 'bank', to: 'newloan', src: 'SCEN' });
    ev.push({ i, kind: 'EXPENSE', amount: ins.interest, from: 'bank', src: 'SCEN' });
  });
  return { accounts: { ...b.accounts, newloan: 0 }, events: ev, count: b.count, newLevel: s.level };
}

function cut(b, pct) {
  return { ...b, events: b.events.map((e) => (e.flex ? { ...e, amount: Math.round(e.amount * (1 - pct / 100)) } : e)) };
}

function kpis(periods) {
  const lowest = periods.reduce((a, p) => (p.low < a.low ? p : a));
  const below = periods.find((p) => p.low < 30000);
  const tot = (f) => periods.reduce((s, p) => s + f(p), 0);
  const gap = Math.round((tot((p) => p.income) - tot((p) => p.expense) - tot((p) => p.principal)) * 24 / periods.length);
  const last = periods[periods.length - 1];
  return { lowest: lowest.low, lowestAt: label(lowest.i), firstBelow: below ? label(below.i) : null, gap, endCard: last.card, endDebt: last.debt };
}

function monthlyLows(periods) {
  const out = [];
  for (let n = 0; n < periods.length; n += 2) out.push(Math.round(Math.min(periods[n].low, periods[n + 1] ? periods[n + 1].low : Infinity) / 1000));
  return out;
}

const b48 = baseline(48), b50 = baseline(50);
const scen = {
  base: { k: kpis(run(b48)), s: monthlyLows(run(b50)) },
  integrate: { k: kpis(run(integrate(b48))), s: monthlyLows(run(integrate(b50))), level: integrate(b48).newLevel },
  cut20: { k: kpis(run(cut(b48, 20))), s: monthlyLows(run(cut(b50, 20))) },
};

// 年度計畫表（2026 全年 + 9–12 月欄位）
const year = {};
const g = {};
let income = 0, expense = 0, card = 0, cardPay = 0;
for (const it of items) {
  const total = it.amt.reduce((s, v) => s + v, 0);
  if (it.type === 'INCOME') income += total;
  if (it.type === 'EXPENSE') { expense += total; g[it.group] = (g[it.group] || 0) + total; if (CARD.has(it.acc)) card += total; }
  if (it.type === 'TRANSFER') cardPay += total;
}
const loanYear = b48.loanLevel * 12;
const cols = [9, 10, 11, 12].map((m) => {
  const inc = items.filter((it) => it.type === 'INCOME').reduce((s, it) => s + it.amt[m], 0);
  const cardM = items.filter((it) => it.type === 'EXPENSE' && CARD.has(it.acc)).reduce((s, it) => s + it.amt[m], 0);
  const nonCard = items.filter((it) => it.type === 'EXPENSE' && !CARD.has(it.acc)).reduce((s, it) => s + it.amt[m], 0);
  const payLoan = items.filter((it) => it.type === 'TRANSFER').reduce((s, it) => s + it.amt[m], 0) + b48.loanLevel;
  return { m, card: cardM, nonCard, payLoan, net: inc - nonCard - payLoan };
});
Object.assign(year, { income, expense, gap: income - expense - loanYear, cardIncrease: card - cardPay, groups: g, loanLevel: b48.loanLevel, cols });

const run48 = run(b48);
const sep = run48.slice(0, 2);
console.log(JSON.stringify({ scen, year, sepCardSpend: sep[0].cardSpend + sep[1].cardSpend, sepCardPay: sep[0].cardPay + sep[1].cardPay }, null, 1));
