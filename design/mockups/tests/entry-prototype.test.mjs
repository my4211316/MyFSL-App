// 記帳畫面互動原型（Entry.dc.html）的操作測試。
// 執行：node --test design/mockups/tests
// 原型的文字規則必須與 app 的 EntryRules.kt 相同。
import { test, mock, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../Entry.dc.html', import.meta.url), 'utf8');
const source = html.match(/<script data-dc-script[^>]*>([\s\S]*?)<\/script>/)[1];
const propsSpec = JSON.parse(html.match(/data-props='([^']*)'/)[1]);

const WARN = '#7A5900';
const NORMAL = '#3F4946';

/** 模擬設計畫布的 DCLogic：setState 立即合併。 */
function mount(props = {}) {
  class DCLogic {
    constructor(p) { this.props = p; this.state = {}; }
    setState(patch) { this.state = { ...this.state, ...patch }; }
  }
  const Component = new Function('DCLogic', `${source}\nreturn Component;`)(DCLogic);
  const c = new Component({ pickCard: propsSpec.pickCard.default, ...props });
  const v = () => c.renderVals();
  const find = (list, pred, what) => {
    const hit = list.find(pred);
    assert.ok(hit, `找不到 ${what}`);
    return hit;
  };
  return {
    c,
    v,
    press: (...keys) => keys.forEach((k) => find(v().keys, (x) => (k === 'back' ? x.isBack : x.label === k), `按鍵 ${k}`).press()),
    type: (label) => find(v().types, (x) => x.label === label, label).pick(),
    item: (name) => find(v().chips, (x) => x.name === name, name).pick(),
    method: (label) => find(v().methods, (x) => x.label === label, label).pick(),
    card: (label) => find(v().cards, (x) => x.label === label, label).pick(),
    note: (label) => find(v().suggestions, (x) => x.label === label, label).pick(),
    save: () => v().save(),
    undo: () => v().undo(),
    selected: (list) => v()[list].filter((x) => x.selected).map((x) => x.label ?? x.name),
  };
}

beforeEach(() => mock.timers.enable({ apis: ['setTimeout'] }));
afterEach(() => mock.timers.reset());

test('初始畫面：生活費・現金（上次用現金）、今天提示列、記下按鈕停用', () => {
  const t = mount();
  const v = t.v();
  assert.equal(v.stripLeft, '9/14 今天已記 3 筆 · $655');
  assert.equal(v.selectedLine, '生活費 · 現金');
  assert.equal(v.amountText, '0');
  assert.equal(v.budgetLine, '生活費・現金 本月剩 $4,600');
  assert.equal(v.budgetColor, NORMAL);
  assert.equal(v.showMethod, true);
  assert.equal(v.showCard, false);
  assert.equal(v.saveEnabled, false);
  assert.equal(v.saveLabel, '輸入金額後記下');
  assert.equal(v.hasSnack, false);
  assert.deepEqual(t.selected('types'), ['支出']);
  assert.deepEqual(t.selected('chips'), ['生活費']);
  assert.deepEqual(t.selected('methods'), ['現金']);
  // 生活費規劃了現金與信用卡，轉帳顯示為未規劃
  assert.deepEqual(v.methods.map((m) => m.planned), [true, true, false]);
});

test('金額鍵盤：前導零、00、倒退、上限 7 位', () => {
  const t = mount();
  t.press('0', '00');
  assert.equal(t.v().amountText, '0');
  t.press('1', '2', '00');
  assert.equal(t.v().amountText, '1,200');
  assert.equal(t.v().saveLabel, '記下 $1,200');
  assert.equal(t.v().saveEnabled, true);
  t.press('back');
  assert.equal(t.v().amountText, '120');
  t.press('back', 'back', 'back', 'back');
  assert.equal(t.v().amountText, '0');
  t.press('9', '9', '9', '9', '9', '9');
  t.press('00');
  assert.equal(t.v().amountText, '999,999', '00 會超過 7 位，整次忽略');
  t.press('9', '9');
  assert.equal(t.v().amountText, '9,999,999');
});

test('預算提示：記下後剩、記下後超出（警示色）', () => {
  const t = mount();
  t.press('1', '2', '0');
  assert.equal(t.v().budgetLine, '記下後 生活費・現金 剩 $4,480');
  t.press('back', 'back', 'back', '4', '6', '0', '0');
  assert.equal(t.v().budgetLine, '記下後 生活費・現金 剩 $0');
  assert.equal(t.v().budgetColor, NORMAL);
  t.press('back', 'back', 'back', 'back', '1', '2', '0', '0', '0');
  assert.equal(t.v().budgetLine, '記下後超出 生活費・現金 計畫 $7,400');
  assert.equal(t.v().budgetColor, WARN);
});

test('付款選信用卡：出現卡片列，預設上次刷的卡；可改不指定', () => {
  const t = mount();
  t.method('信用卡');
  let v = t.v();
  assert.equal(v.showCard, true);
  assert.deepEqual(t.selected('cards'), ['信用卡 A']);
  assert.equal(v.selectedLine, '生活費 · 信用卡 · 信用卡 A');
  assert.equal(v.budgetLine, '生活費・信用卡 本月剩 $6,200');
  t.card('不指定');
  assert.equal(t.v().selectedLine, '生活費 · 信用卡 · 不指定');
  t.method('信用卡');
  assert.deepEqual(t.selected('cards'), ['不指定'], '再點同一個付款方式不會重設卡片');
  t.method('現金');
  assert.equal(t.v().showCard, false);
});

test('關閉「指定信用卡」：沒有卡片列，記下時不歸屬卡片', () => {
  const t = mount({ pickCard: false });
  t.method('信用卡');
  assert.equal(t.v().showCard, false);
  assert.equal(t.v().selectedLine, '生活費 · 信用卡');
  t.press('1', '5', '0');
  t.save();
  assert.equal(t.c.state.entries[0].method, 'CARD');
  assert.equal(t.c.state.entries[0].card, null);
});

test('切換項目：預設支付方式與卡片依規則帶入，備註清空', () => {
  const t = mount();
  t.note('早餐');
  t.item('交通油資');
  assert.equal(t.v().selectedLine, '交通油資 · 信用卡 · 不指定', '沒刷過：計畫最大的是信用卡，卡片不指定');
  assert.deepEqual(t.selected('suggestions'), []);
  assert.equal(t.v().budgetLine, '交通油資・信用卡 本月剩 $1,200');
  t.item('汽車保養');
  assert.equal(t.v().selectedLine, '汽車保養 · 信用卡 · 不指定');
  t.item('才藝課');
  assert.equal(t.v().selectedLine, '才藝課 · 轉帳');
  t.item('家用');
  assert.equal(t.v().selectedLine, '家用 · 現金');
  t.item('其他');
  assert.equal(t.v().selectedLine, '其他 · 現金');
  assert.equal(t.v().budgetLine, '不在本月計畫內，會列入計畫外支出');
  assert.equal(t.v().budgetColor, NORMAL);
});

test('記住上次：交通油資刷 B 卡後，再回來預設 B 卡；生活費改用信用卡後預設信用卡', () => {
  const t = mount();
  t.item('交通油資');
  t.card('信用卡 B');
  t.press('8', '0', '0');
  t.save();
  t.item('生活費');
  t.item('交通油資');
  assert.equal(t.v().selectedLine, '交通油資 · 信用卡 · 信用卡 B');

  t.item('生活費');
  t.method('信用卡');
  t.press('1', '5', '0');
  t.save();
  t.item('家用');
  t.item('生活費');
  assert.equal(t.v().selectedLine, '生活費 · 信用卡 · 信用卡 A');
});

test('沒規劃的支付方式：警示並算進項目總額', () => {
  const t = mount();
  t.method('轉帳');
  assert.equal(t.v().budgetLine, '生活費沒有規劃用轉帳，會算進生活費總額（剩 $10,800）');
  assert.equal(t.v().budgetColor, WARN);
  t.press('5', '0', '0');
  assert.equal(t.v().budgetLine, '生活費沒有規劃用轉帳，會算進生活費總額（剩 $10,300）');
  t.save();
  assert.equal(t.v().snack, '已記下 生活費 $500', '沒規劃的支付方式不附剩餘');
  assert.equal(t.v().budgetLine, '生活費沒有規劃用轉帳，會算進生活費總額（剩 $10,300）');
  t.method('現金');
  assert.equal(t.v().budgetLine, '生活費・現金 本月剩 $4,600', '個別支付方式的剩餘不受影響');
});

test('記下：清空金額與備註、更新提示列與預算、顯示提示；復原後還原', () => {
  const t = mount();
  t.note('早餐');
  assert.deepEqual(t.selected('suggestions'), ['早餐']);
  t.press('8', '5');
  t.save();
  let v = t.v();
  assert.equal(v.snack, '已記下 早餐 $85 · 生活費・現金剩 $4,515');
  assert.equal(v.hasSnack, true);
  assert.equal(v.amountText, '0');
  assert.deepEqual(t.selected('suggestions'), []);
  assert.equal(v.stripLeft, '9/14 今天已記 4 筆 · $740');
  assert.equal(v.budgetLine, '生活費・現金 本月剩 $4,515');
  assert.equal(v.selectedLine, '生活費 · 現金', '項目與付款保留，方便連續記帳');

  t.undo();
  v = t.v();
  assert.equal(v.hasSnack, false);
  assert.equal(v.stripLeft, '9/14 今天已記 3 筆 · $655');
  assert.equal(v.budgetLine, '生活費・現金 本月剩 $4,600');
});

test('復原只移除這次記下的，不會刪到回報前的紀錄', () => {
  const t = mount();
  t.press('1');
  t.save();
  t.undo();
  t.undo();
  assert.equal(t.v().stripLeft, '9/14 今天已記 3 筆 · $655');
});

test('金額為 0 時按記下沒有反應', () => {
  const t = mount();
  t.save();
  assert.equal(t.v().hasSnack, false);
  assert.equal(t.v().stripLeft, '9/14 今天已記 3 筆 · $655');
});

test('超支後：本月已超出（警示色），提示顯示負的剩餘', () => {
  const t = mount();
  t.press('5', '0', '0', '0');
  t.save();
  assert.equal(t.v().snack, '已記下 生活費 $5,000 · 生活費・現金剩 −$400');
  assert.equal(t.v().budgetLine, '生活費・現金 本月已超出 $400');
  assert.equal(t.v().budgetColor, WARN);
  t.press('1', '0', '0');
  assert.equal(t.v().budgetLine, '記下後超出 生活費・現金 計畫 $500');
});

test('備註建議：再點一次取消', () => {
  const t = mount();
  t.note('午餐');
  t.note('晚餐');
  assert.deepEqual(t.selected('suggestions'), ['晚餐']);
  t.note('晚餐');
  assert.deepEqual(t.selected('suggestions'), []);
});

test('收入：沒有付款列、不影響支出進度、不計入今天支出', () => {
  const t = mount();
  t.type('收入');
  let v = t.v();
  assert.equal(v.showMethod, false);
  assert.equal(v.showCard, false);
  assert.equal(v.selectedLine, '薪資 · 入帳：薪轉帳戶');
  assert.equal(v.budgetLine, '不影響支出進度');
  assert.deepEqual(v.chips.map((c) => c.name), ['薪資', '教育補助', '其他收入']);
  t.press('6', '5', '0', '0', '0');
  t.save();
  v = t.v();
  assert.equal(v.snack, '已記下 薪資 $65,000');
  assert.equal(v.stripLeft, '9/14 今天已記 3 筆 · $655');
  assert.equal(t.c.state.entries[0].method, null);
});

test('轉帳：顯示轉出轉入帳戶；切回支出回到第一個項目', () => {
  const t = mount();
  t.type('轉帳');
  assert.equal(t.v().selectedLine, '繳信用卡 A · 由薪轉帳戶付款');
  t.item('領現');
  t.note('ATM');
  assert.equal(t.v().selectedLine, '領現 · 由薪轉帳戶轉入零用現金');
  t.type('支出');
  assert.equal(t.v().selectedLine, '生活費 · 現金');
  assert.deepEqual(t.selected('suggestions'), []);
});

test('提示 4 秒後自動消失；連續記下會重新計時', () => {
  const t = mount();
  t.press('1');
  t.save();
  mock.timers.tick(3000);
  t.press('2');
  t.save();
  mock.timers.tick(3999);
  assert.equal(t.v().hasSnack, true);
  assert.equal(t.v().snack, '已記下 生活費 $2 · 生活費・現金剩 $4,597');
  mock.timers.tick(1);
  assert.equal(t.v().hasSnack, false);
});

test('卸載時清掉計時器', () => {
  const t = mount();
  t.press('1');
  t.save();
  t.c.componentWillUnmount();
  mock.timers.tick(5000);
  assert.equal(t.v().hasSnack, true, '計時器已取消，不會在卸載後改狀態');
});
