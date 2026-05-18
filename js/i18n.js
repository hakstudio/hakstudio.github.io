// ========================================
// i18n — Language switching with JSON translations
// ========================================

const I18N_STORAGE_KEY = 'hak-lang';
let translations = {};
let currentLang = 'en';

document.addEventListener('DOMContentLoaded', () => {
  // Load saved language or default to English
  const saved = localStorage.getItem(I18N_STORAGE_KEY);
  currentLang = saved || 'en';

  loadLanguage(currentLang);
  initLangSwitchers();
});

async function loadLanguage(lang) {
  try {
    // Determine base path (works for both root and subpages)
    const basePath = window.location.pathname.includes('portfolio')
      ? 'i18n/' : 'i18n/';
    
    const response = await fetch(`${basePath}${lang}.json`);
    if (!response.ok) throw new Error(`Failed to load ${lang}.json`);
    
    translations = await response.json();
    currentLang = lang;
    applyTranslations();
    updateActiveLangBtn();
    localStorage.setItem(I18N_STORAGE_KEY, lang);
    document.documentElement.lang = lang;
  } catch (err) {
    console.warn(`i18n: Could not load ${lang}.json`, err);
  }
}

function applyTranslations() {
  document.querySelectorAll('[data-i18n]').forEach(el => {
    const key = el.getAttribute('data-i18n');
    const value = getNestedValue(translations, key);
    if (value) {
      el.textContent = value;
    }
  });
}

// Get nested object value from dot-notation key like "nav.about"
function getNestedValue(obj, key) {
  return key.split('.').reduce((o, k) => (o && o[k] !== undefined ? o[k] : null), obj);
}

function initLangSwitchers() {
  document.querySelectorAll('.lang-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const lang = btn.dataset.lang;
      if (lang && lang !== currentLang) {
        loadLanguage(lang);
      }
    });
  });
}

function updateActiveLangBtn() {
  document.querySelectorAll('.lang-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.lang === currentLang);
  });
}
