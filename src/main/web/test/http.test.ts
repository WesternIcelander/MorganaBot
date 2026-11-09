import { test } from "node:test";
import assert from "node:assert/strict";
import { api, ApiFailure, CSRF_HEADER, failureFrom, setCsrfToken, setSessionExpiredHandler } from "../ts/http.js";

interface Call {
    url: string;
    init: RequestInit;
}

function stubFetch(status: number, body: string | null): Call[] {
    const calls: Call[] = [];
    (globalThis as { fetch: unknown }).fetch = async (url: string, init: RequestInit) => {
        calls.push({ url, init });
        return new Response(body, { status, headers: { "Content-Type": "application/json" } });
    };
    return calls;
}

test("failureFrom reads the API's error body", () => {
    const failure = failureFrom(400, { error: "Bad name", code: "invalid", field: "twitchName" });
    assert.equal(failure.message, "Bad name");
    assert.equal(failure.code, "invalid");
    assert.equal(failure.field, "twitchName");
    assert.equal(failureFrom(502, "nope").code, "http_502");
    assert.ok(failureFrom(401, null).sessionExpired);
});

test("writes send the CSRF header and JSON; reads don't send the header", async () => {
    setCsrfToken("tok");
    let calls = stubFetch(200, "{\"ok\":true}");
    const result = await api<{ ok: boolean }>("PUT", "/guilds/1/me/streamer/template", { template: "hi" });
    assert.deepEqual(result, { ok: true });
    const headers = calls[0]!.init.headers as Record<string, string>;
    assert.equal(calls[0]!.url, "/dashboard/api/guilds/1/me/streamer/template");
    assert.equal(headers[CSRF_HEADER], "tok");
    assert.equal(headers["Content-Type"], "application/json");
    assert.equal(calls[0]!.init.body, "{\"template\":\"hi\"}");

    calls = stubFetch(200, "{}");
    await api("GET", "/me");
    assert.equal((calls[0]!.init.headers as Record<string, string>)[CSRF_HEADER], undefined);

    calls = stubFetch(204, null);
    assert.equal(await api("DELETE", "/guilds/1/me/streamer"), undefined);
    assert.equal((calls[0]!.init.headers as Record<string, string>)[CSRF_HEADER], "tok");
    assert.equal(calls[0]!.init.body, undefined);
});

test("a 401 calls the session expired handler and rejects", async () => {
    let expired = 0;
    setSessionExpiredHandler(() => expired++);
    stubFetch(401, "{\"error\":\"Not logged in\",\"code\":\"not_logged_in\"}");
    let failure: unknown = null;
    try {
        await api("GET", "/me");
    } catch (error) {
        failure = error;
    }
    assert.ok(failure instanceof ApiFailure);
    assert.equal((failure as ApiFailure).code, "not_logged_in");
    assert.equal(expired, 1);
});

test("field errors come through for the form to show", async () => {
    stubFetch(400, "{\"error\":\"Unknown Twitch name\",\"code\":\"invalid\",\"field\":\"twitchName\"}");
    try {
        await api("PUT", "/guilds/1/me/streamer/twitch", { twitchName: "x" });
        assert.ok(false, "should have failed");
    } catch (error) {
        assert.equal((error as ApiFailure).field, "twitchName");
    }
});
