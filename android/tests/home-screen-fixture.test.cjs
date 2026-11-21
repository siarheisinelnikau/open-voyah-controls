const {test} = require('node:test');
const assert = require('node:assert/strict');
const fixture = require('./fixtures/launcher-home-h97c.json');

// Structure-only capture of the factory launcher home window (see docs/HOME_SCREEN_MAP.md).
const ALLOWED = new Set(['i', 'parent', 'class', 'id', 'bounds', 'visibility', 'clickable']);
const nodes = fixture.nodes;
const children = index => nodes.filter(node => node.parent === index);
const descendants = index => children(index).flatMap(child => [child, ...descendants(child.i)]);
const byId = id => nodes.filter(node => node.id === id);

test('home fixture keeps structure only, never screen text', () => {
    for (const node of nodes) {
        for (const key of Object.keys(node)) assert.ok(ALLOWED.has(key), `node ${node.i} has ${key}`);
        assert.equal(node.bounds.length, 4);
        assert.ok(node.bounds.every(Number.isInteger));
    }
});

test('home fixture is a single tree in pre-order', () => {
    nodes.forEach((node, index) => {
        assert.equal(node.i, index);
        if (index === 0) assert.equal(node.parent, null);
        else assert.ok(node.parent >= 0 && node.parent < index, `node ${index} parent ${node.parent}`);
    });
    assert.equal(nodes[0].id, 'main_root');
});

test('home fixture holds the pager pages and cards the map describes', () => {
    const [pager] = byId('home_view_pager');
    assert.equal(pager.class, 'com.qinggan.app.launcher.widget.SlowViewPager');
    const [cards] = byId('rcy_view');
    const cardClasses = children(cards.i)
        .map(slot => descendants(slot.i).find(node => /\.(homecard|v97c)\./.test(node.class))?.class.split('.').pop());
    assert.deepEqual(cardClasses, ['HomeNaviCardView', 'HomeMediaView97c', 'HomeWeatherCardView',
        'HomeAppCardView', 'HomeSeatCardView', 'HomeFragranceCardView']);
    const [grid] = byId('all_app_view');
    assert.equal(grid.class, 'com.qinggan.launcher.base.allapp.AllAppBarView');
    assert.ok(children(grid.i).every(tile => tile.id === 'all_app_item' && tile.clickable));
    assert.equal(byId('screen_down_main')[0].visibility, 'GONE');
});
