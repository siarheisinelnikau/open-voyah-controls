/** ART may expose zero-valued static fields before a class has been initialized. */
export function initializedType(Java, name) {
    const type = Java.use(name);
    Java.use('java.lang.Class').forName(name, true, type.class.getClassLoader());
    return type;
}
