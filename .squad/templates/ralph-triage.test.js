#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const {
  isUntriagedIssue,
  parseOwnerLabels,
  parseRoster,
  parseRoutingRules,
  triageIssue,
} = require('./ralph-triage.js');

const squadDir = path.resolve(__dirname, '..');
const routingMd = fs.readFileSync(path.join(squadDir, 'routing.md'), 'utf8');
const teamMd = fs.readFileSync(path.join(squadDir, 'team.md'), 'utf8');
const rules = parseRoutingRules(routingMd);
const roster = parseRoster(teamMd);
const ownerLabels = parseOwnerLabels(teamMd);

const cases = [
  ['UI', 'Switch'],
  ['sync', 'Neo'],
  ['testing', 'Mouse'],
  ['architecture', 'Tank'],
  ['Work queue monitoring', 'Ralph'],
];

for (const [title, expectedAgent] of cases) {
  const decision = triageIssue({ title, body: '' }, rules, [], roster);
  assert.ok(decision, `Expected a routing decision for "${title}"`);
  assert.equal(decision.agent.name, expectedAgent, `"${title}" should route to ${expectedAgent}`);
  console.log(`✓ ${title} → ${expectedAgent}`);
}

const weightedCases = [
  ['Phone sync failure', 'Neo', 'work-type token "sync"'],
  ['Watch heart rate sensor crash', 'Trinity', 'example token "sensor"'],
  ['Phone and watch UI/UX sync failure', 'Switch', 'work-type phrase "phone and watch ui ux"'],
  ['Wear Data Layer sensor failure', 'Neo', 'example phrase "wear data layer"'],
  ['API integration failure', 'Neo', 'work-type token "integration"'],
  ['Architectural refactor', 'Tank', 'work-type token "architecture"'],
  ['Unit/integration/device tests', 'Mouse', 'example phrase "unit integration device tests"'],
  ['architectural claims', 'Fact Checker', 'example phrase "architecture claims"'],
];

for (const [title, expectedAgent, expectedReason] of weightedCases) {
  const decision = triageIssue({ title, body: '' }, rules, [], roster);
  assert.ok(decision, `Expected a weighted routing decision for "${title}"`);
  assert.equal(decision.agent.name, expectedAgent, `"${title}" should route to ${expectedAgent}`);
  assert.ok(decision.reason.includes(expectedReason), `"${title}" should explain its decisive routing signal`);
  console.log(`✓ ${title} → ${expectedAgent} (${expectedReason})`);
}

const testingRule = rules.find((rule) => rule.workType === 'Testing and release quality');
const verificationRule = rules.find((rule) => rule.workType === 'Factual or source verification');
assert.ok(testingRule, 'Expected the testing routing rule');
assert.ok(verificationRule, 'Expected the verification routing rule');
assert.ok(
  !testingRule.exampleTokens.includes('integration'),
  'An example token must not collide with another rule’s normalized work-type token',
);
assert.ok(
  testingRule.examplePhrases.includes('unit integration device tests'),
  'Suppressing a colliding example token must retain the full testing example phrase',
);
assert.ok(
  !verificationRule.exampleTokens.includes('architecture'),
  'A normalized example token must not collide with another rule’s work-type token',
);
assert.ok(
  verificationRule.examplePhrases.includes('architecture claims'),
  'Suppressing a colliding example token must retain the full verification example phrase',
);
console.log('✓ cross-rule example-token collisions are suppressed without losing phrases');

for (const title of ['Gradle build failure', 'Repository maintenance']) {
  const decision = triageIssue({ title, body: '' }, rules, [], roster);
  assert.ok(decision, `Expected a fallback routing decision for "${title}"`);
  assert.equal(decision.agent.name, 'Tank', `"${title}" should route to Tank`);
  assert.equal(decision.source, 'lead-fallback', `"${title}" should use the architecture/lead fallback`);
  console.log(`✓ ${title} → Tank (lead fallback)`);
}

assert.equal(
  isUntriagedIssue({ labels: ['squad', 'squad:ralph'] }, ownerLabels),
  false,
  'The Ralph owner label must prevent a second ownership label',
);
assert.equal(
  isUntriagedIssue({ labels: ['squad', 'squad:untriaged'] }, ownerLabels),
  true,
  'The reserved squad:untriaged state label must not count as ownership',
);
assert.equal(
  isUntriagedIssue({ labels: ['squad', 'squad:not-a-member'] }, ownerLabels),
  true,
  'Unknown squad:* labels must not count as ownership',
);
assert.equal(
  isUntriagedIssue({ labels: ['SQUAD', 'SQUAD:NEO'] }, ownerLabels),
  false,
  'A current roster owner label must prevent a second ownership label case-insensitively',
);
assert.equal(
  isUntriagedIssue({ labels: ['squad', 'SQUAD:COPILOT'] }, ownerLabels),
  false,
  'The Copilot owner label must count as ownership when @copilot is present in team.md',
);
assert.ok(roster.some((member) => member.name === 'Ralph'), 'Ralph must be routable');
assert.ok(!roster.some((member) => member.name === 'Scribe'), 'Scribe must remain non-routable');
for (const member of roster) {
  assert.ok(ownerLabels.has(member.label.toLowerCase()), `Expected ${member.label} in the owner-label set`);
}
assert.ok(ownerLabels.has('squad:ralph'), 'Expected squad:ralph in the parsed owner-label set');
assert.ok(ownerLabels.has('squad:copilot'), 'Expected squad:copilot in the parsed owner-label set');
console.log('✓ only current roster/Copilot labels block reassignment');
