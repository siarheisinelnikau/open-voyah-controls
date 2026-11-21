const CODE = /^[a-z][a-z0-9_]{0,39}$/;
const JAVA_TYPE = /(?:[a-z_][a-z0-9_]*\.)+([A-Z][A-Za-z0-9_$]*(?:Exception|Error))\b/;
const JS_TYPE = /^[A-Za-z]{1,40}$/;

/**
 * Names a failure without quoting it. An explicit outcome code wins, then the Java exception class, then the
 * JavaScript error type. The message is never used: it can carry package names, paths or user text.
 */
export function errorKind(error) {
    if (typeof error?.code === 'string' && CODE.test(error.code)) return error.code;
    let text = '';
    try { text = String(error); } catch (_) { }
    const java = JAVA_TYPE.exec(text);
    if (java) return java[1];
    return typeof error?.name === 'string' && JS_TYPE.test(error.name) ? error.name : 'Error';
}

function field(value) {
    const text = String(value);
    // '$' is part of a nested class name such as Resources$NotFoundException.
    return /^[A-Za-z0-9_.:/$-]{1,64}$/.test(text) ? text : 'invalid';
}

/**
 * Bounded diagnostics for code that runs inside an OEM process. Each distinct problem is printed when it first
 * happens and again at 10, 100, 1000... occurrences, so a failing hot path cannot flood hook.log while a recurring
 * fault still shows its scale. At most [limit] distinct problems are tracked per reporter.
 */
export function createReporter(marker, { limit = 32, write = line => console.log(line) } = {}) {
    const seen = new Map();
    return function report(stage, error, fields = {}) {
        try {
            let line = marker + ' stage=' + field(stage) + ' kind=' + field(errorKind(error));
            for (const [name, value] of Object.entries(fields)) line += ' ' + field(name) + '=' + field(value);
            // The rendered line is the identity of a problem: the same stage failing for another cause is new.
            const known = seen.get(line);
            if (known === undefined && seen.size >= limit) return;
            const count = (known ?? 0) + 1;
            seen.set(line, count);
            if (count === 1) write(line);
            else if (/^10+$/.test(String(count))) write(line + ' repeated=' + count);
        } catch (_) {
            // Reporting runs inside hooked OEM methods and must never become the failure.
        }
    };
}
