// Remote-control navigation for pages built for a mouse.
// Arrow keys move a yellow focus ring to the nearest clickable thing in that
// direction; OK clicks it. Pages that handle arrow keys themselves (and call
// preventDefault) keep full control.
(function () {
  if (window.__tvnav) return;
  window.__tvnav = true;

  var SEL = 'a[href],button,input,select,textarea,summary,[tabindex]:not([tabindex="-1"]),' +
    '[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[onclick]';
  var NATIVE = /^(A|BUTTON|INPUT|SELECT|TEXTAREA|SUMMARY)$/;
  var DIRS = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] };

  function addStyle() {
    if (!document.head) return;
    var st = document.createElement('style');
    st.textContent = ':focus{outline:3px solid #ffd400 !important;outline-offset:2px !important;}';
    document.head.appendChild(st);
  }
  addStyle();

  function usable(el) {
    if (el.disabled) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 3 || r.height < 3) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && s.pointerEvents !== 'none';
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function pick(dir, from) {
    var all = Array.prototype.slice.call(document.querySelectorAll(SEL));
    var best = null, bestScore = Infinity;
    var fc = from ? center(from.getBoundingClientRect()) : null;
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el === from || !usable(el)) continue;
      var r = el.getBoundingClientRect();
      var c = center(r);
      var score;
      if (!fc) {
        // Nothing focused yet: start from the top-left of what's on screen.
        if (r.bottom < 0 || r.top > innerHeight) continue;
        score = c.y * 2 + c.x;
      } else {
        var dx = c.x - fc.x, dy = c.y - fc.y;
        var along = dx * dir[0] + dy * dir[1];
        if (along <= 1) continue;
        var across = Math.abs(dx * dir[1] + dy * dir[0]);
        score = along + across * 2.5;
      }
      if (score < bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function focus(el) {
    if (!NATIVE.test(el.tagName) && !el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
  }

  window.addEventListener('keydown', function (e) {
    if (e.defaultPrevented) return;
    var active = document.activeElement;
    var hasFocus = active && active !== document.body && active !== document.documentElement;

    if (e.key === 'Enter') {
      if (hasFocus && !NATIVE.test(active.tagName)) {
        active.click();
        e.preventDefault();
      }
      return;
    }

    var dir = DIRS[e.key];
    if (!dir) return;
    if (hasFocus && /^(INPUT|TEXTAREA)$/.test(active.tagName) && dir[0] !== 0) return;

    var next = pick(dir, hasFocus ? active : null);
    if (next) {
      focus(next);
      e.preventDefault();
    }
  }, false);
})();
