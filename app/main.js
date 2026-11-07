/* Arr411 bootstrap: tabs, modules, links and files shared from Android. */
(function () {
  'use strict';
  const App = window.App;
  const h = App.h;

  App.loadCfg();
  if (App.config.lidarrCategory == null) App.saveCfg({ lidarrCategory: 'lidarr' });
  const box = document.getElementById('tabs');
  for (const name of ['qbit', 'radarr', 'sonarr', 'lidarr', 'settings']) {
    const el = h('div.tab', { 'data-tab': name });
    box.appendChild(el);
    App.registerTab(name, el);
  }
  const boot = [
    ['qBittorrent', () => App.qbit.init()],
    ['Radarr', () => App.makeArr('radarr').init()],
    ['Sonarr', () => App.makeArr('sonarr').init()],
    ['Lidarr', () => App.makeLidarr().init()],
    ['Réglages', () => App.settings.init()]
  ];
  for (const b of boot) {
    try {
      b[1]();
    } catch (e) {
      console.error(e);
      App.toast('Erreur au démarrage (' + b[0] + ') : ' + e.message, 'err');
    }
  }

  // ---------- Shared links / files ----------
  function arrFor(svc, term) {
    const M = App.arrUI && App.arrUI[svc];
    if (M) M.openAdd(term);
  }
  async function askArr(term) {
    App.goTab(App.state.tab === 'sonarr' ? 'sonarr' : 'radarr');
    const svc = await App.pick({
      title: 'Ajouter avec…',
      options: [
        { value: 'radarr', label: 'Radarr', sub: 'C’est un film', icon: 'film' },
        { value: 'sonarr', label: 'Sonarr', sub: 'C’est une série', icon: 'tv' }
      ]
    });
    if (svc) arrFor(svc, term);
  }
  function toQbit(o) {
    App.goTab('qbit');
    return App.qbit.openAdd(o);
  }
  // d = { files: [{ name, base64 }] } or { text } (magnet, .torrent URL, C411 / IMDb / TMDB / TVDB / MusicBrainz link, title).
  App.handleShared = function (d) {
    if (typeof d === 'string') {
      try {
        d = JSON.parse(d);
      } catch (e) {
        d = { text: d };
      }
    }
    d = d || {};
    if (d.tab) return d.tab === 'settings' ? App.openSettings(d.section) : App.goTab(d.tab);
    if (d.files && d.files.length) return toQbit({ files: d.files });
    const text = String(d.text || '').trim();
    if (!text) return;
    const magnets = text.match(/magnet:\?[^\s"'<>]+/gi);
    if (magnets) return toQbit({ urls: magnets });
    const url = (text.match(/https?:\/\/[^\s"'<>]+/i) || [])[0] || '';
    if (/^https?:\/\/([\w-]+\.)*c411\.org(\/|$)/i.test(url)) return App.openC411(url);
    if (/\.torrent([?#]|$)/i.test(url)) return toQbit({ urls: [url] });
    let m;
    if ((m = /themoviedb\.org\/movie\/(\d+)/i.exec(text))) return arrFor('radarr', 'tmdb:' + m[1]);
    if ((m = /themoviedb\.org\/tv\/(\d+)/i.exec(text))) return arrFor('sonarr', 'tmdb:' + m[1]);
    if ((m = /thetvdb\.com\/(?:dereferrer\/)?series\/(\d+)/i.exec(text))) return arrFor('sonarr', 'tvdb:' + m[1]);
    if ((m = /musicbrainz\.org\/(?:artist|release-group|release)\/[0-9a-f-]{36}/i.exec(text))) return arrFor('lidarr', 'https://' + m[0]);
    if ((m = /imdb\.com\/(?:[a-z-]+\/)?title\/(tt\d+)/i.exec(text)) || (m = /\b(tt\d{6,})\b/.exec(text))) return askArr('imdb:' + m[1].toLowerCase());
    if (url) return App.toast('Lien non reconnu : ' + url.slice(0, 60), 'err');
    App.c411Search(text.slice(0, 120));
  };

  // ---------- Start ----------
  const hash = /[#&]tab=(\w+)/.exec(location.hash);
  App.showTab((hash && hash[1]) || App.nat('currentTab') || (window.Native ? 'c411' : 'qbit'));
  App.nat('ready');
})();
