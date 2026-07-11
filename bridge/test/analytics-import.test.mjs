import assert from "node:assert/strict";
import test from "node:test";
import { prepareAnalyticsImport, trendTolerance } from "../src/analytics-import.js";

const DAY = 24 * 60 * 60 * 1000;
const TODAY = Date.UTC(2026, 6, 11);

test("analytics import accepts a movement trend matching trusted server anchors", () => {
  const result = prepareAnalyticsImport({
    movements: movements(),
    currentFollowers: 1_000,
    existing: [trusted(TODAY - 2 * DAY, 994), trusted(TODAY - DAY, 997), trusted(TODAY, 1_000)],
    now: TODAY + 12 * 60 * 60 * 1000,
  });

  assert.equal(result.ok, true);
  assert.equal(result.checkedAnchors, 3);
  assert.deepEqual(result.samples.map((sample) => sample.followers), [994, 997]);
  assert.ok(result.samples.every((sample) => sample.src === "x_analytics"));
});

test("analytics import refuses an edited trend outside the tiny margin", () => {
  const result = prepareAnalyticsImport({
    movements: movements().map((item, index) => index === 2 ? { ...item, newFollows: 50 } : item),
    currentFollowers: 1_000,
    existing: [trusted(TODAY - 2 * DAY, 994), trusted(TODAY - DAY, 997), trusted(TODAY, 1_000)],
    now: TODAY,
  });

  assert.equal(result.ok, false);
  assert.equal(result.error, "analytics_trend_mismatch");
  assert.ok(result.detail.difference > result.detail.tolerance);
});

test("analytics import refuses when the server has no older trusted anchor", () => {
  const result = prepareAnalyticsImport({
    movements: movements(),
    currentFollowers: 1_000,
    existing: [trusted(TODAY, 1_000)],
    now: TODAY,
  });

  assert.deepEqual(result, { ok: false, error: "insufficient_trusted_history" });
});

test("analytics import tolerates small removal drift and omits impossible follower days", () => {
  const input = Array.from({ length: 31 }, (_, offset) => ({
    date: new Date(TODAY - (30 - offset) * DAY).toISOString().slice(0, 10),
    newFollows: offset === 28 ? 4 : 0,
    unfollows: 0,
  }));
  const result = prepareAnalyticsImport({
    movements: input,
    currentFollowers: 1,
    existing: [trusted(TODAY - DAY, 1), trusted(TODAY, 1)],
    now: TODAY,
  });

  assert.equal(result.ok, true);
  assert.equal(result.samples.length, 2);
  assert.ok(result.samples.every((sample) => sample.followers >= 0));
});

test("analytics import still rejects removal drift outside the time-scaled tolerance", () => {
  const result = prepareAnalyticsImport({
    movements: [
      { date: "2026-07-10", newFollows: 0, unfollows: 0 },
      { date: "2026-07-11", newFollows: 20, unfollows: 0 },
    ],
    currentFollowers: 10,
    existing: [trusted(TODAY - DAY, 10), trusted(TODAY, 10)],
    now: TODAY,
  });

  assert.equal(result.ok, false);
  assert.equal(result.error, "analytics_impossible_followers");
  assert.equal(result.detail.reconstructed, -10);
  assert.equal(result.detail.tolerance, 3);
});

test("trend tolerance grows slowly over long exports", () => {
  assert.equal(trendTolerance(7), 3);
  assert.equal(trendTolerance(7, 7_647), 8);
  assert.equal(trendTolerance(31), 4);
  assert.equal(trendTolerance(365), 26);
});

function movements() {
  return [
    { date: "2026-07-09", newFollows: 1, unfollows: 3 },
    { date: "2026-07-10", newFollows: 4, unfollows: 1 },
    { date: "2026-07-11", newFollows: 5, unfollows: 2 },
  ];
}

function trusted(ts, followers) {
  return { ts, followers, followersKnown: true, src: "live" };
}
