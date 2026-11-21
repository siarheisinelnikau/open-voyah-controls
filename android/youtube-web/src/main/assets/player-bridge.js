// Reports the page's video state to the wrapper and lets it pause or resume the player. Injected after every page
// load; YouTube is a single-page app, so the listeners stay for later videos. Media events do not bubble, so the
// listeners capture them on the document.
(function () {
    var host = window.OvcYouTubeHost;
    if (!host) return;
    if (window.__ovcYouTube) { window.__ovcYouTube.report(); return; }
    var last = '';
    function videos() { return Array.prototype.slice.call(document.querySelectorAll('video')); }
    function current() {
        var all = videos();
        for (var i = 0; i < all.length; i++) if (!all[i].paused && !all[i].ended) return all[i];
        return all[0] || null;
    }
    function metadata() {
        var m = navigator.mediaSession && navigator.mediaSession.metadata;
        return {title: (m && m.title) || document.title || '', artist: (m && m.artist) || ''};
    }
    function report() {
        var video = current();
        var info = metadata();
        var state = {
            playing: !!(video && !video.paused && !video.ended),
            title: info.title,
            artist: info.artist,
            position: video ? Math.round(video.currentTime * 1000) : 0,
            duration: video && isFinite(video.duration) ? Math.round(video.duration * 1000) : 0
        };
        var text = JSON.stringify(state);
        if (text !== last) { last = text; host.state(text); }
    }
    ['play', 'playing', 'pause', 'ended', 'emptied', 'durationchange', 'seeked'].forEach(function (name) {
        document.addEventListener(name, report, true);
    });
    function click(selectors) {
        for (var i = 0; i < selectors.length; i++) {
            var button = document.querySelector(selectors[i]);
            if (button) { button.click(); return true; }
        }
        return false;
    }
    window.__ovcYouTube = {
        report: report,
        pause: function () { videos().forEach(function (v) { v.pause(); }); },
        play: function () { var v = current(); if (v) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } },
        next: function () {
            click(['.player-controls-middle-core-buttons button[aria-label*="Next"]', 'button.player-control-next',
                '.ytp-next-button', 'ytm-playlist-panel-renderer .playlist-next']);
        },
        previous: function () {
            var v = current();
            if (v && v.currentTime > 5) { v.currentTime = 0; return; }
            if (!click(['.player-controls-middle-core-buttons button[aria-label*="Previous"]', 'button.player-control-previous',
                '.ytp-prev-button'])) history.back();
        },
        seek: function (ms) { var v = current(); if (v) v.currentTime = ms / 1000; }
    };
    report();
})();
