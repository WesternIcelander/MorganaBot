// The bits of Node's built-in test runner the tests use (there are no @types/node, to keep typescript the only
// dependency).
declare module "node:test" {
    export function test(name: string, fn: () => void | Promise<void>): void;
}

declare module "node:assert/strict" {
    const assert: {
        equal(actual: unknown, expected: unknown, message?: string): void;
        deepEqual(actual: unknown, expected: unknown, message?: string): void;
        ok(value: unknown, message?: string): void;
    };
    export default assert;
}
