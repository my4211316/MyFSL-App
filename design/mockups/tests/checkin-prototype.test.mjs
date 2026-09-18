// 本週檢查畫面（CheckIn.dc.html）的操作測試。
// 執行：node --test design/mockups/tests/checkin-prototype.test.mjs
// 文字與判斷規則必須與 app 的 CheckInRules.kt 相同。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../CheckIn.dc.html', import.meta.url), 'utf8');
const source = html.match(/<script data-dc-script[^>]*>([\s\S]*?)<\/script>/)[1];
const propsSpec = JSON.parse(html.match(/data-props='([^']*)'/)[1]);

const WARN = '#7A5900';
const NORMAL = '#3F4946';

function mount(props = {}) {
  class DCLogic {
    constructor(p) { this.props = p; this.state = {}; }
    setState(patch) { this.state = { ...this.state, ...patch }; }
  }
  const Component = new Function('DCLogic', `${source}\nreturn Component;`)(DCLogic);
  const c = new Component({ cardPostingDays: propsSpec.cardPostingDays.default, ...props });
  const v = () => c.renderVals();
  const find = (list, pred, what) => {
    const hit = list.find(pred);
    assert.ok(hit, `找不到 ${what}`);
    return hit;
  };
  const row = (name) => find(v().rows, (r) => r.name === name, name);
  return {
    c,
    v,
    row,
    confirm: (name) => find(v().confirms, (x) => x.name === name, name),
    focusField: (rowName, label) => find(row(rowName).fields, (f) => f.label === label, label).tap(),
    press: (...keys) => keys.forEach((k) => find(v().keys, (x) => (k === 'back' ? x.isBack : x.label === k), `按鍵 ${k}`).press()),
    type: (text) => String(text).split('').forEach((ch) => find(v().keys, (x) => x.label === ch, ch).press()),
    choose: (name, label) => find(mountChoices(v(), name), (x) => x.label === label, label).pick(),
    resolution: (rowName, label) => find(row(rowName).resolutions, (x) => x.label === label, label).pick(),
    assign: (rowName, itemName) => find(row(rowName).assignItems, (x) => x.name === itemName, itemName).pick(),
    save: () => v().save.press(),
    again: () => v().again(),
    preview: () => v().preview.map((p) => p.text),
  };
  function mountChoices(vals, name) {
    return find(vals.confirms, (x) => x.name === name, name).choices;
  }
}

test('初始畫面：3 個對帳列、2 項到期確認、還不能完成', () => {
  const t = mount();
  const v = t.v();
  assert.equal(v.title, '本週檢查');
  assert.deepEqual(v.rows.map((r) => r.name), ['薪轉帳戶', '零用現金', '信用卡合計']);
  assert.deepEqual(v.rows.map((r) => r.hint), ['系統推算 $120,000', '系統推算 $15,000', '系統推算欠款 $105,000']);
  assert.deepEqual(v.confirms.map((c) => c.name), ['汽車保養', '教育補助（收入）']);
  assert.deepEqual(v.confirms[0].choices.map((c) => c.label), ['已付', '金額不同', '延到下月']);
  assert.deepEqual(v.confirms[1].choices.map((c) => c.label), ['已入帳', '金額不同', '延到下月'], '收入用「已入帳」');
  assert.equal(t.row('零用現金').message, '填入實際金額就會比對');
  assert.equal(t.row('信用卡合計').message, '兩張卡都填了才能對帳');
  assert.equal(v.save.enabled, false);
  assert.equal(v.save.label, '沒有要寫入的東西');
  assert.deepEqual(t.preview(), ['目前沒有要寫入的東西']);
  assert.equal(v.rows.every((r) => r.showResolutions === false), true);
});

test('錢包少了 500：預設歸到生活費，可改項目或只校正餘額', () => {
  const t = mount();
  t.focusField('零用現金', '錢包總額');
  t.type('14500');
  let row = t.row('零用現金');
  assert.equal(row.fields[0].value, '14,500');
  assert.equal(row.kind, 'MISSED');
  assert.equal(row.message, '少了 $500，可能是漏記');
  assert.equal(row.messageColor, WARN);
  assert.equal(row.showResolutions, true);
  assert.deepEqual(row.resolutions.filter((o) => o.selected).map((o) => o.label), ['歸到項目']);
  assert.equal(row.showAssign, true);
  assert.deepEqual(row.assignItems.filter((a) => a.selected).map((a) => a.name), ['生活費']);
  assert.deepEqual(t.preview(), ['漏記差額 1 筆 · $500（生活費）', '校正餘額 1 個帳戶']);
  assert.equal(t.v().save.enabled, true);

  t.assign('零用現金', '家用');
  assert.deepEqual(t.preview(), ['漏記差額 1 筆 · $500（家用）', '校正餘額 1 個帳戶']);

  t.resolution('零用現金', '只校正餘額');
  assert.equal(t.row('零用現金').showAssign, false);
  assert.deepEqual(t.preview(), ['校正餘額 1 個帳戶']);

  t.resolution('零用現金', '先不處理');
  assert.deepEqual(t.preview(), ['目前沒有要寫入的東西']);
  assert.equal(t.v().save.enabled, false);
});

test('錢包剛好相符或比推算多', () => {
  const t = mount();
  t.focusField('零用現金', '錢包總額');
  t.press('相符');
  let row = t.row('零用現金');
  assert.equal(row.fields[0].value, '15,000');
  assert.equal(row.kind, 'MATCHED');
  assert.equal(row.message, '和推算一樣');
  assert.equal(row.messageColor, NORMAL);
  assert.equal(row.showResolutions, false);
  assert.deepEqual(t.preview(), ['校正餘額 1 個帳戶']);

  t.press('back', 'back', 'back', 'back', 'back', 'back');
  t.type('15300');
  row = t.row('零用現金');
  assert.equal(row.kind, 'OVER_RECORDED');
  assert.equal(row.message, '多了 $300，可能是記重複或有收入沒記');
  assert.deepEqual(row.resolutions.filter((o) => o.selected).map((o) => o.label), ['只校正餘額']);
});

test('信用卡：銀行多就是漏記，少一點是還沒入帳', () => {
  const t = mount();
  t.focusField('信用卡合計', '信用卡 A');
  t.type('60650');
  assert.equal(t.row('信用卡合計').message, '兩張卡都填了才能對帳');
  t.focusField('信用卡合計', '信用卡 B');
  t.type('45000');
  let row = t.row('信用卡合計');
  assert.equal(row.kind, 'MISSED');
  assert.equal(row.message, '銀行多 $650，可能有刷卡沒記');
  assert.deepEqual(row.assignItems.map((a) => a.name), ['生活費', '交通油資']);
  assert.deepEqual(t.preview(), ['漏記差額 1 筆 · $650（生活費）', '校正餘額 2 個帳戶']);

  // 銀行比推算少，但不超過最近 5 天刷的 12,100：視為還沒入帳，預設先不處理
  t.focusField('信用卡合計', '信用卡 A');
  t.press('back', 'back', 'back', 'back', 'back');
  t.type('59000');
  row = t.row('信用卡合計');
  assert.equal(row.kind, 'PENDING');
  assert.equal(row.message, '銀行少 $1,000，可能是最近 5 天的刷卡還沒入帳');
  assert.deepEqual(row.resolutions.filter((o) => o.selected).map((o) => o.label), ['先不處理']);
  assert.deepEqual(t.preview(), ['目前沒有要寫入的東西']);

  // 差太多就不是時間差
  t.focusField('信用卡合計', '信用卡 A');
  t.press('back', 'back', 'back', 'back', 'back');
  t.type('47899');
  row = t.row('信用卡合計');
  assert.equal(row.kind, 'OVER_RECORDED');
  assert.equal(row.message, '銀行少 $12,101，可能是記重複或有退款');
});

test('入帳天數可以調整', () => {
  const t = mount({ cardPostingDays: 3 });
  t.focusField('信用卡合計', '信用卡 A');
  t.type('59000');
  t.focusField('信用卡合計', '信用卡 B');
  t.type('45000');
  assert.equal(t.row('信用卡合計').message, '銀行少 $1,000，可能是最近 3 天的刷卡還沒入帳');
});

test('相符：信用卡合計會把另一張卡補成 0', () => {
  const t = mount();
  t.focusField('信用卡合計', '信用卡 A');
  t.press('相符');
  const row = t.row('信用卡合計');
  assert.deepEqual(row.fields.map((f) => f.value), ['105,000', '0']);
  assert.equal(row.kind, 'MATCHED');
});

test('到期確認：已付會算進卡片推算，對完帳不算漏記', () => {
  const t = mount();
  t.choose('汽車保養', '已付');
  assert.equal(t.row('信用卡合計').hint, '系統推算欠款 $117,000');
  t.focusField('信用卡合計', '信用卡 A');
  t.type('72000');
  t.focusField('信用卡合計', '信用卡 B');
  t.type('45000');
  const row = t.row('信用卡合計');
  assert.equal(row.kind, 'MATCHED');
  assert.deepEqual(t.preview(), ['到期確認 1 項', '校正餘額 2 個帳戶']);
});

test('到期確認：金額不同會跳出欄位並改變銀行推算；延到下月不影響', () => {
  const t = mount();
  t.choose('教育補助（收入）', '金額不同');
  assert.equal(t.confirm('教育補助（收入）').showAmount, true);
  t.type('4500');
  assert.equal(t.confirm('教育補助（收入）').field.value, '4,500');
  assert.equal(t.row('薪轉帳戶').hint, '系統推算 $124,500');
  assert.deepEqual(t.preview(), ['到期確認 1 項']);

  t.choose('教育補助（收入）', '延到下月');
  assert.equal(t.confirm('教育補助（收入）').showAmount, false);
  assert.equal(t.row('薪轉帳戶').hint, '系統推算 $120,000');
  assert.deepEqual(t.preview(), ['到期確認 1 項']);

  // 再點一次取消選擇
  t.choose('教育補助（收入）', '延到下月');
  assert.deepEqual(t.preview(), ['目前沒有要寫入的東西']);
});

test('完成檢查：列出寫入內容，可以再檢查一次', () => {
  const t = mount();
  t.focusField('零用現金', '錢包總額');
  t.type('14500');
  t.choose('汽車保養', '已付');
  const expected = t.preview();
  t.save();
  const v = t.v();
  assert.equal(v.done, true);
  assert.equal(v.editing, false);
  assert.deepEqual(v.doneLines.map((d) => d.text), expected);
  t.again();
  assert.equal(t.v().editing, true);
  assert.equal(t.row('零用現金').fields[0].value, '14,500', '再檢查時保留剛才輸入的數字');
});

test('多記差額也會寫入', () => {
  const t = mount();
  t.focusField('零用現金', '錢包總額');
  t.type('15300');
  t.resolution('零用現金', '歸到項目');
  assert.deepEqual(t.preview(), ['多記差額 −$300（生活費）', '校正餘額 1 個帳戶']);
});

test('金額上限 9 位數', () => {
  const t = mount();
  t.focusField('薪轉帳戶', '實際餘額');
  t.type('1234567890');
  assert.equal(t.row('薪轉帳戶').fields[0].value, '123,456,789');
});
