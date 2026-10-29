import { expect, test } from 'vitest';

// Temporary: proves CI fails when a test fails (QA-024). Removed in the next commit.
test('deliberate failure', () => {
  expect(1 + 1).toBe(3);
});
