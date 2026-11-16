import {initializedType} from './java-types.js';

/** Recognize the observed record format without exposing any account fields. */
export function classifyAccountRecord(raw) {
    if (typeof raw !== 'string' || raw.length === 0 || raw.length > 8192) return 'unavailable';
    raw = raw.replace(/\r?\n$/, '');
    if (/[\u0000-\u001f\u007f\ufffd]/.test(raw)) return 'unavailable';
    if (raw === 'guest') return 'guest';
    const fields = raw.split('%');
    if (fields.length < 5) return 'unavailable';
    const user = fields[fields.length - 1], account = fields[fields.length - 2], owner = fields[fields.length - 3];
    return /^(0|[1-9][0-9]{0,9})$/.test(user) && Number(user) <= 2147483647
        && account.trim().length > 0 && account.length <= 256 && ['0', '1'].includes(owner)
        && fields.slice(0, -4).join('%').trim().length > 0 ? 'identified' : 'unavailable';
}

/** This is a bounded observation of one backing file, never an account backup. */
export function observeAccountState(Java) {
    let descriptor, Os;
    try {
        Os = Java.use('android.system.Os');
        const C = initializedType(Java, 'android.system.OsConstants');
        const path = '/private/configs/token/accountInfo';
        const File = Java.use('java.io.File');
        if (String(File.$new(path).getCanonicalPath()) !== path) return 'unavailable';
        const before = Os.lstat(path);
        const stamp = stat => [stat.st_dev.value, stat.st_ino.value, stat.st_size.value, stat.st_mtime.value, stat.st_ctime.value].map(String).join(':');
        const size = Number(before.st_size.value);
        if (!C.S_ISREG(before.st_mode.value) || !Number.isSafeInteger(size) || size < 1 || size > 8192) return 'unavailable';
        descriptor = Os.open(path, C.O_RDONLY.value | C.O_NOFOLLOW.value | C.O_CLOEXEC.value, 0);
        if (stamp(Os.fstat(descriptor)) !== stamp(before)) return 'unstable';
        const bytes = Java.array('byte', Array(size + 1).fill(0));
        const read = () => {
            let used = 0;
            while (used < bytes.length) {
                const count = Os.read.overload('java.io.FileDescriptor', '[B', 'int', 'int')
                    .call(Os, descriptor, bytes, used, bytes.length - used);
                if (count <= 0) break;
                used += count;
            }
            if (used !== size) return null;
            return String(Java.use('java.lang.String').$new(bytes, 0, used, 'UTF-8'));
        };
        const first = read();
        Os.lseek(descriptor, 0, C.SEEK_SET.value);
        const second = read();
        if (first === null || first !== second || stamp(Os.fstat(descriptor)) !== stamp(before)
            || stamp(Os.lstat(path)) !== stamp(before)) return 'unstable';
        return classifyAccountRecord(first);
    } catch (_) { return 'unavailable'; }
    finally { if (descriptor && Os) { try { Os.close(descriptor); } catch (_) { } } }
}
