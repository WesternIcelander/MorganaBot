// The fetch helper for the dashboard API: JSON in and out, the CSRF header on every write, and one place that
// notices an expired session.

import type { ErrorBody } from "./api.js";

export const API_BASE = "/dashboard/api";
export const CSRF_HEADER = "X-Morgana-CSRF";

/** An API call that failed: the server's error, or a network problem (status 0). */
export class ApiFailure extends Error {
    readonly status: number;
    readonly code: string;
    readonly field: string | null;

    constructor(status: number, code: string, message: string, field: string | null = null) {
        super(message);
        this.name = "ApiFailure";
        this.status = status;
        this.code = code;
        this.field = field;
    }

    /** The session is gone (expired, logged out, or the bot restarted). */
    get sessionExpired(): boolean {
        return this.status === 401 || this.code === "not_logged_in";
    }
}

let csrfToken: string | null = null;
let onSessionExpired: (() => void) | null = null;

/** The session's CSRF token, from `GET /me`. */
export function setCsrfToken(token: string | null): void {
    csrfToken = token;
}

/** Called once a request finds the session has expired, e.g. to show "run /dashboard again". */
export function setSessionExpiredHandler(handler: () => void): void {
    onSessionExpired = handler;
}

export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/** Turns an error response's status and body into an {@link ApiFailure}. */
export function failureFrom(status: number, body: unknown): ApiFailure {
    if (body !== null && typeof body === "object") {
        const error = body as Partial<ErrorBody>;
        if (typeof error.error === "string" && typeof error.code === "string") {
            return new ApiFailure(status, error.code, error.error, typeof error.field === "string" ? error.field : null);
        }
    }
    if (status === 401) return new ApiFailure(status, "not_logged_in", "You're not logged in.");
    return new ApiFailure(status, "http_" + status, "The server answered with an error (" + status + "). Try again.");
}

/**
 * Calls the API. `path` is relative to `/dashboard/api` (e.g. `/me`). A body is sent as JSON. Resolves with the parsed
 * response (undefined for 204), or rejects with an {@link ApiFailure}.
 */
export async function api<T>(method: Method, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { "Accept": "application/json" };
    if (method !== "GET") {
        if (csrfToken !== null) headers[CSRF_HEADER] = csrfToken;
    }
    const init: RequestInit = { method, headers, credentials: "same-origin", cache: "no-store" };
    if (body !== undefined) {
        headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(body);
    }
    let response: Response;
    try {
        response = await fetch(API_BASE + path, init);
    } catch {
        throw new ApiFailure(0, "network", "Couldn't reach the dashboard. Check your connection and try again.");
    }
    let parsed: unknown = undefined;
    if (response.status !== 204) {
        const text = await response.text();
        if (text.length > 0) {
            try {
                parsed = JSON.parse(text);
            } catch {
                parsed = undefined;
            }
        }
    }
    if (!response.ok) {
        const failure = failureFrom(response.status, parsed);
        if (failure.sessionExpired && onSessionExpired !== null) onSessionExpired();
        throw failure;
    }
    return parsed as T;
}

/** Puts a path segment (an ID) into a URL safely. */
export function seg(value: string): string {
    return encodeURIComponent(value);
}
