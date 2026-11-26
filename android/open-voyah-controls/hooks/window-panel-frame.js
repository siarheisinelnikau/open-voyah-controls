/** A full-height left rail occupies one inset. An overlay that covers only part of the edge does not. */
export function launcherPanelEdge(windows, display) {
    const [left, top, right, bottom] = display;
    let width = null;
    for (const window of windows) {
        if (!window || !window.visible || !window.alive || window.displayId !== 0 || window.pkg !== 'com.qinggan.app.launcher' ||
            window.type !== 2110 || window.title !== 'MainScreenNavigationBar') continue;
        const [x, y, end, height] = window.frame;
        if (x !== left || y > top || height < bottom || end > right || end - left < 1 || end - left > 600) continue;
        width = Math.max(width ?? 0, end - left);
    }
    return width;
}
