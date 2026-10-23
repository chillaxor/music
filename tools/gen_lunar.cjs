// 从 assets/lunar.min.js（lunar-javascript 1.7.7）生成 Kotlin 农历数据表 + 经典算法对拍验证
// 表格式（经典 lunarInfo，每项一个 Int）：
//   bits 0-3  : 闰月月份（0=无闰月）
//   bit  16   : 闰月是否大月30天（1=30，0=29）
//   bits 4-15 : 12个月是否大月30天，月 m 占 bit(16-m)（月1→bit15，月12→bit4）
// 库的约定：闰月月份为负数（Lunar.getMonth() 闰月返回负值）；LunarMonth.fromYm(y,±m).getDayCount() 直接给月长
const L = require('../app/src/main/assets/lunar.min.js');
const { Lunar, LunarYear, LunarMonth } = L;

const Y0 = 1900, Y1 = 2099;

const table = [];
for (let y = Y0; y <= Y1; y++) {
  const leap = LunarYear.fromYear(y).getLeapMonth(); // 0=无闰月，否则为正月份
  let v = leap & 0xf;
  for (let m = 1; m <= 12; m++) {
    const days = LunarMonth.fromYm(y, m).getDayCount();
    if (days !== 29 && days !== 30) throw new Error(`月天数异常 ${y}/${m}: ${days}`);
    if (days === 30) v |= (1 << (16 - m));
  }
  if (leap > 0) {
    const days = LunarMonth.fromYm(y, -leap).getDayCount();
    if (days === 30) v |= (1 << 16);
  }
  table.push(v);
}

// ============ 经典算法 JS 移植（与 Kotlin 版逐行对应，用于对拍） ============
function leapMonthOf(idx) { return table[idx] & 0xf; }
function leapDaysOf(idx) { return leapMonthOf(idx) ? ((table[idx] & 0x10000) ? 30 : 29) : 0; }
function monthDaysOf(idx, m) { return (table[idx] & (0x10000 >> m)) ? 30 : 29; }
function yearDaysOf(idx) {
  let sum = 348;
  for (let i = 0x8000; i > 0x8; i >>= 1) sum += (table[idx] & i) ? 1 : 0;
  return sum + leapDaysOf(idx);
}
function ymdToOrdinal(y, m, d) {
  return Math.round((Date.UTC(y, m - 1, d) - Date.UTC(1970, 0, 1)) / 86400000);
}
const BASE_ORD = ymdToOrdinal(1900, 1, 31); // 1900-01-31 = 农历 1900 正月初一
function algo(y, m, d) {
  let offset = ymdToOrdinal(y, m, d) - BASE_ORD;
  let i, temp = 0;
  for (i = Y0; i <= Y1 && offset > 0; i++) {
    temp = yearDaysOf(i - Y0);
    offset -= temp;
  }
  if (offset < 0 && temp > 0) { offset += temp; i--; }
  const lunarYear = i;
  const leap = leapMonthOf(lunarYear - Y0);
  let isLeap = false;
  for (i = 1; i < 13 && offset > 0; i++) {
    if (leap > 0 && i === leap + 1 && !isLeap) { i--; isLeap = true; temp = leapDaysOf(lunarYear - Y0); }
    else temp = monthDaysOf(lunarYear - Y0, i);
    if (isLeap && i === leap + 1) isLeap = false;
    offset -= temp;
  }
  if (offset === 0 && leap > 0 && i === leap + 1) {
    if (isLeap) isLeap = false; else { isLeap = true; i--; }
  }
  if (offset < 0) { offset += temp; i--; }
  return { y: lunarYear, m: i, d: offset + 1, leap: isLeap };
}

// ============ 对拍：1900-02-01 ~ 2099-12-31 每一天 ============
let checked = 0, bad = 0;
for (let t = Date.UTC(1900, 1, 1); t <= Date.UTC(2099, 11, 31); t += 86400000) {
  const dt = new Date(t);
  const y = dt.getUTCFullYear(), m = dt.getUTCMonth() + 1, d = dt.getUTCDate();
  const a = algo(y, m, d);
  const lib = Lunar.fromDate(dt);
  const libMonth = lib.getMonth(); // 闰月为负
  const ok = a.y === lib.getYear() && (a.leap ? -a.m : a.m) === libMonth && a.d === lib.getDay();
  checked++;
  if (!ok) {
    bad++;
    if (bad <= 5) console.log(`MISMATCH ${y}-${m}-${d}: algo=${JSON.stringify(a)} lib=${lib.getYear()}/${libMonth}/${lib.getDay()}`);
  }
}
console.log(`checked=${checked} bad=${bad}`);

// 抽样输出关键日期
for (const s of ['2026-10-23', '2026-2-17', '2025-1-29', '2026-6-19', '2033-11-23', '1900-09-24']) {
  const [y, m, d] = s.split('-').map(Number);
  const a = algo(y, m, d);
  console.log(`${s} -> 农历${a.leap ? '闰' : ''}${a.m}月${a.d}日 (algo) | lib: ${Lunar.fromDate(new Date(Date.UTC(y, m - 1, d))).toString()}`);
}

// 输出 Kotlin 表
const lines = [];
for (let i = 0; i < table.length; i += 10) {
  lines.push('        ' + table.slice(i, i + 10).map(v => `0x${(v >>> 0).toString(16).padStart(5, '0')}`).join(', ') + ',');
}
const path = require('path');
require('fs').writeFileSync(path.join(__dirname, 'lunar_table.kt.txt'), lines.join('\n'));
console.log('table entries:', table.length);
