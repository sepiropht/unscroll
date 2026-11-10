import { describe, expect, it } from 'vitest';
import {
  bestMatchingDomain,
  domainMatches,
  hostFromUrl,
  normalizeDomainInput,
  parseTabUrl,
  safeReturnUrl,
} from '../src/shared/domain.ts';

describe('normalisation des URLs', () => {
  it('réduit une URL, un port ou un www à un hôte nu', () => {
    expect(normalizeDomainInput('https://www.YouTube.com/watch?v=1')).toBe('youtube.com');
    expect(normalizeDomainInput('WWW.Example.com.')).toBe('example.com');
    expect(normalizeDomainInput('localhost:3000')).toBe('localhost');
    expect(normalizeDomainInput('example.com/path?q=1#top')).toBe('example.com');
    expect(normalizeDomainInput('https://user:secret@news.example.com/a')).toBe('news.example.com');
    expect(normalizeDomainInput('http://127.0.0.1:8080/app')).toBe('127.0.0.1');
    expect(normalizeDomainInput('not a domain')).toBeNull();
    expect(normalizeDomainInput('javascript:alert(1)')).toBeNull();
  });

  it('ne compte que les pages http(s), pas l’écran de blocage', () => {
    expect(hostFromUrl('https://www.youtube.com/feed')).toBe('youtube.com');
    expect(hostFromUrl('javascript:alert(1)')).toBeNull();
    expect(hostFromUrl('chrome://extensions')).toBeNull();

    const block = 'chrome-extension://abc/block.html';
    expect(parseTabUrl('https://www.youtube.com/watch?v=1', block)).toEqual({
      countHost: 'youtube.com',
      displayHost: 'youtube.com',
    });
    expect(parseTabUrl(`${block}?host=https%3A%2F%2Fwww.youtube.com%2Ffeed`, block)).toEqual({
      countHost: null,
      displayHost: 'youtube.com',
    });
  });

  it('apparie un domaine et ses sous-domaines, pas un suffixe piégé', () => {
    expect(domainMatches('a.b.com', 'b.com')).toBe(true);
    expect(domainMatches('b.com', 'b.com')).toBe(true);
    expect(domainMatches('notb.com', 'b.com')).toBe(false);
    expect(domainMatches('b.com.evil.com', 'b.com')).toBe(false);
    expect(bestMatchingDomain('mail.google.com', ['google.com', 'mail.google.com'])).toBe('mail.google.com');
  });

  it('n’accepte un retour qu’en http(s) vers le domaine bloqué', () => {
    expect(safeReturnUrl('https://www.youtube.com/feed', 'youtube.com')).toBe('https://www.youtube.com/feed');
    expect(safeReturnUrl('https://music.youtube.com/a', 'youtube.com')).toBe('https://music.youtube.com/a');
    expect(safeReturnUrl('https://evil.test/', 'youtube.com')).toBeNull();
    expect(safeReturnUrl('https://youtube.com.evil.test/', 'youtube.com')).toBeNull();
    expect(safeReturnUrl('javascript:alert(1)', 'youtube.com')).toBeNull();
  });
});
