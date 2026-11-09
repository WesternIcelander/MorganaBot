// Small DOM helpers. Everything from the server, Discord or Twitch is inserted as text (text nodes / textContent),
// never as HTML, so names, titles and templates can't inject markup.

import { ApiFailure } from "./http.js";

export type Child = Node | string | number | null | undefined | false | Child[];

export interface Attrs {
    class?: string;
    id?: string;
    title?: string;
    href?: string;
    type?: string;
    name?: string;
    value?: string;
    placeholder?: string;
    for?: string;
    role?: string;
    hidden?: boolean;
    disabled?: boolean;
    rows?: number;
    maxlength?: number;
    min?: number;
    max?: number;
    step?: number;
    inputmode?: string;
    checked?: boolean;
    open?: boolean;
    novalidate?: boolean;
    colspan?: number;
    scope?: string;
    autocomplete?: string;
    target?: string;
    rel?: string;
    src?: string;
    alt?: string;
    "aria-label"?: string;
    "aria-labelledby"?: string;
    "aria-live"?: string;
    "aria-describedby"?: string;
    "aria-disabled"?: string;
    onclick?: (event: MouseEvent) => void;
    onsubmit?: (event: SubmitEvent) => void;
    oninput?: (event: Event) => void;
    onchange?: (event: Event) => void;
}

/** Creates an element. String children become text nodes. */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs | null = null, ...children: Child[]): HTMLElementTagNameMap[K] {
    const element = document.createElement(tag);
    if (attrs !== null) {
        for (const [key, value] of Object.entries(attrs)) {
            if (value === undefined || value === null || value === false) continue;
            if (key.startsWith("on") && typeof value === "function") {
                element.addEventListener(key.substring(2), value as EventListener);
            } else if (key === "class") {
                element.className = String(value);
            } else if (value === true) {
                element.setAttribute(key, "");
            } else {
                element.setAttribute(key, String(value));
            }
        }
    }
    append(element, children);
    return element;
}

/** Appends children (text for strings and numbers; nulls and falses are skipped; arrays are flattened). */
export function append(parent: Node, children: Child[]): void {
    for (const child of children) {
        if (child === null || child === undefined || child === false) continue;
        if (Array.isArray(child)) {
            append(parent, child);
        } else if (child instanceof Node) {
            parent.appendChild(child);
        } else {
            parent.appendChild(document.createTextNode(String(child)));
        }
    }
}

/** Replaces an element's content. */
export function replace(parent: Element, ...children: Child[]): void {
    parent.replaceChildren();
    append(parent, children);
}

/** A time (ms since the epoch, as the API sends it) in the viewer's locale. */
export function formatTime(ms: string | number | null): string {
    if (ms === null) return "";
    const value = typeof ms === "string" ? Number(ms) : ms;
    if (!Number.isFinite(value) || value <= 0) return "";
    return new Date(value).toLocaleString();
}

/** The message for any error a call can throw. */
export function errorMessage(error: unknown): string {
    if (error instanceof ApiFailure) return error.message;
    if (error instanceof Error) return error.message;
    return "Something went wrong. Try again.";
}

/**
 * Error display for a form: an error shown next to the field it belongs to (by the API's `field`), or at the form
 * level when it has no field or the field isn't on this form.
 */
export class FormErrors {
    private readonly fields = new Map<string, { input: HTMLElement; message: HTMLElement }>();
    readonly general: HTMLElement;

    constructor() {
        this.general = h("p", { class: "form-error", role: "alert", hidden: true });
    }

    /** Registers a field. Put the returned element right after the input. */
    field(name: string, input: HTMLElement): HTMLElement {
        const id = "err-" + name + "-" + Math.random().toString(36).substring(2, 8);
        const message = h("p", { class: "field-error", id, role: "alert", hidden: true });
        input.setAttribute("aria-describedby", id);
        this.fields.set(name, { input, message });
        return message;
    }

    clear(): void {
        this.general.hidden = true;
        this.general.textContent = "";
        for (const { input, message } of this.fields.values()) {
            message.hidden = true;
            message.textContent = "";
            input.removeAttribute("aria-invalid");
        }
    }

    /** Shows an error from a failed call. Session expiry is handled by the shell, so it's not repeated here. */
    show(error: unknown): void {
        this.clear();
        const field = error instanceof ApiFailure ? error.field : null;
        const target = field !== null ? this.fields.get(field) : undefined;
        if (target !== undefined) {
            target.message.textContent = errorMessage(error);
            target.message.hidden = false;
            target.input.setAttribute("aria-invalid", "true");
        } else {
            this.general.textContent = errorMessage(error);
            this.general.hidden = false;
        }
    }
}

/** Runs `action` with `button` disabled, so a slow request can't be sent twice. */
export async function busy<T>(button: HTMLButtonElement, action: () => Promise<T>): Promise<T> {
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    try {
        return await action();
    } finally {
        button.disabled = false;
        button.removeAttribute("aria-busy");
    }
}
