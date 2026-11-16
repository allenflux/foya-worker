'use strict';

window.AppLanguage = (() => {
  const preference = 'foya-language';
  let language = 'zh-CN';
  try {
    const saved = localStorage.getItem(preference) ?? localStorage.getItem('p0-lookup-language');
    if (saved === 'en') language = 'en';
  } catch { /* Language switching still works without storage. */ }

  const chinese = document.getElementById('app-lang-zh');
  const english = document.getElementById('app-lang-en');
  const attributes = ['aria-label', 'placeholder', 'title'];
  const staticText = Array.from(document.querySelectorAll('[data-en]'), node => ({
    node, zh: node.textContent, en: node.dataset.en
  }));
  const staticAttributes = attributes.flatMap(name =>
    Array.from(document.querySelectorAll(`[data-en-${name}]`), node => ({
      node, name, zh: node.getAttribute(name), en: node.getAttribute(`data-en-${name}`)
    })));

  function render(previous) {
    document.documentElement.lang = language;
    chinese.setAttribute('aria-pressed', String(language === 'zh-CN'));
    english.setAttribute('aria-pressed', String(language === 'en'));
    for (const label of staticText) {
      if (label.node.textContent === label[previous]) {
        label.node.textContent = label[language === 'en' ? 'en' : 'zh'];
      }
    }
    for (const label of staticAttributes) {
      if (label.node.getAttribute(label.name) === label[previous]) {
        label.node.setAttribute(label.name, label[language === 'en' ? 'en' : 'zh']);
      }
    }
  }

  function choose(next) {
    if (language === next) return;
    const previous = language === 'en' ? 'en' : 'zh';
    language = next;
    try { localStorage.setItem(preference, next); } catch { /* Use the in-memory preference. */ }
    render(previous);
    window.dispatchEvent(new Event('app-language-change'));
  }

  chinese.addEventListener('click', () => choose('zh-CN'));
  english.addEventListener('click', () => choose('en'));
  render('zh');
  return {
    locale: () => language,
    t: (zh, en) => language === 'en' ? en : zh
  };
})();
