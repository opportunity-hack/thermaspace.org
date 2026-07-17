// Span bar: scroll progress on the iron-LUT gradient (decoration only).
(function () {
  var tick = document.getElementById('spanTick');
  if (!tick) return;
  function update() {
    var max = document.documentElement.scrollHeight - window.innerHeight;
    var t = max > 0 ? window.scrollY / max : 0;
    if (t < 0) t = 0; if (t > 1) t = 1;
    tick.style.top = (100 - t * 100) + '%';
  }
  window.addEventListener('scroll', update, { passive: true });
  window.addEventListener('resize', update);
  update();
})();

// Feature videos: play only while on screen (saves battery + bandwidth).
(function () {
  var videos = document.querySelectorAll('video[data-inview]');
  if (!('IntersectionObserver' in window)) {
    videos.forEach(function (v) { v.play().catch(function () {}); });
    return;
  }
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (e.isIntersecting) e.target.play().catch(function () {});
      else e.target.pause();
    });
  }, { threshold: 0.25 });
  videos.forEach(function (v) { io.observe(v); });
})();

// GA4 events. Enhanced measurement covers page views, 90% scroll, and outbound
// links — but our conversions (Google Form signups, Stripe checkout clicks) and
// .stl downloads (not on GA's file-download extension list) are named here.
(function () {
  function track(name, params) {
    if (typeof gtag === 'function') gtag('event', name, params);
  }
  function linkLocation(a) {
    var s = a.closest('section');
    if (s && s.id) return s.id;
    if (s && s.classList.contains('hero')) return 'hero';
    if (a.closest('header')) return 'nav';
    if (a.closest('footer')) return 'footer';
    return 'page';
  }

  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest && ev.target.closest('a[href]');
    if (!a) return;
    var href = a.getAttribute('href');

    if (href.indexOf('https://buy.stripe.com/') === 0) {
      track('mount_order', {
        link_location: linkLocation(a), value: 19, currency: 'USD'
      });
    } else if (href.indexOf('https://forms.gle/') === 0) {
      var name = /beta/i.test(a.textContent) ? 'join_beta' : 'contact_form';
      track(name, { link_location: linkLocation(a) });
    } else if (href.indexOf('/stl/') === 0) {
      track('file_download', {
        file_name: href.split('/').pop(),
        file_extension: 'stl',
        link_text: a.textContent.trim(),
        link_url: href
      });
    }
  });

  // Section funnel: how far down the page (hero → features → mount → faq)
  // visitors actually get. Fires once per section per page view.
  if ('IntersectionObserver' in window) {
    var seen = {};
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        var id = e.target.id;
        if (!e.isIntersecting || seen[id]) return;
        seen[id] = true;
        track('section_view', { section_id: id });
        io.unobserve(e.target);
      });
    }, { threshold: 0.3 });
    document.querySelectorAll('section[id]').forEach(function (s) { io.observe(s); });
  }

  // FAQ opens ('toggle' doesn't bubble — capture phase catches it).
  document.addEventListener('toggle', function (ev) {
    var d = ev.target;
    if (d.tagName === 'DETAILS' && d.open) {
      var q = d.querySelector('summary');
      track('faq_open', { question: q ? q.textContent.trim() : '' });
    }
  }, true);
})();
