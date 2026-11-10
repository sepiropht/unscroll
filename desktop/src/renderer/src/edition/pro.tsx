import { formatDayLabel, formatDuration } from '@shared/format'
import { DEFAULT_SYNC_URL } from '@shared/sync'
import type { AdvancedReport, Locale, Snapshot, SyncPublicState } from '@shared/types'
import { toDataURL } from 'qrcode'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { EmptyState } from '../empty'
import { Meter } from '../meter'

export type EditionPageId = 'sync' | 'report'

type ProCopy = {
  sync: string
  report: string
  syncTitle: string
  syncHelp: string
  url: string
  token: string
  passphrase: string
  tokenKeep: string
  enable: string
  clear: string
  clearPassphrase: string
  save: string
  push: string
  pull: string
  https: string
  ready: string
  advanced: string
  advancedHelp: string
  saved: string
  provisioned: string
  accountSaved: string
  pushed: string
  pulled: string
  unchanged: string
  pair: string
  hideQr: string
  pairWarning: string
  qrAlt: string
  link: string
  copyLink: string
  copied: string
  copyFailed: string
  qrTooBig: string
  paste: string
  pasteHelp: string
  pasteAction: string
  pairNeedSave: string
  paired: string
  reportTitle: string
  reportHelp: string
  thisWeek: string
  prevWeek: string
  delta: string
  apps: string
  empty: string
  loading: string
}

const copy: Record<Locale, ProCopy> = {
  fr: {
    sync: 'Synchronisation',
    report: 'Rapport',
    syncTitle: 'Synchronisation',
    syncHelp:
      'Désactivée tant que vous ne l’activez pas. L’activation crée un compte anonyme et une clé de 32 octets qui reste sur cet ordinateur. Vous n’avez rien à saisir. Le serveur ne stocke que le chiffré. L’adresse par défaut est https://sync.unscroll.elimbi.com. Changez-la pour un serveur à vous.',
    url: 'URL du serveur',
    token: 'Jeton',
    passphrase: 'Phrase secrète',
    tokenKeep: 'Laisser vide pour garder le jeton ou la clé déjà enregistrés.',
    enable: 'Activer la synchronisation',
    clear: 'Effacer le jeton enregistré',
    clearPassphrase: 'Effacer la clé ou la phrase enregistrée',
    save: 'Enregistrer',
    push: 'Envoyer l’instantané',
    pull: 'Recevoir l’instantané',
    https: 'HTTPS vérifie le certificat. L’adresse par défaut est https://sync.unscroll.elimbi.com. Sur cette machine, http://127.0.0.1 reste possible.',
    ready: 'Une clé ou une phrase est déjà enregistrée sur cet ordinateur.',
    advanced: 'Avancé',
    advancedHelp:
      'Pour un coffre déjà créé ou une phrase secrète ancienne. Laissez vide pour garder l’enregistré. Un compte nouveau n’utilise pas ces champs.',
    saved: 'Enregistré. Aucun envoi tant que vous ne le demandez pas.',
    provisioned: 'Compte créé. La synchronisation est lancée. La clé reste sur cet ordinateur.',
    accountSaved: 'Le compte est enregistré sur cet ordinateur. La synchronisation n’a pas abouti.',
    pushed: 'Instantané envoyé.',
    pulled: 'Instantané reçu.',
    unchanged: 'Aucun instantané sur le serveur.',
    pair: 'Associer un appareil',
    hideQr: 'Masquer le QR',
    pairWarning:
      'Ce QR et ce lien donnent l’accès complet au coffre (adresse, jeton et clé de chiffrement). Traitez-les comme un mot de passe.',
    qrAlt: 'QR d’association',
    link: 'Lien d’association',
    copyLink: 'Copier le lien',
    copied: 'Lien copié.',
    copyFailed: 'La copie a échoué. Sélectionnez le lien.',
    qrTooBig: 'Le QR est trop long. Copiez le lien.',
    paste: 'Coller un lien',
    pasteHelp: 'Le lien enregistre le compte et lance la synchronisation.',
    pasteAction: 'Associer',
    pairNeedSave: 'Activez d’abord la synchronisation.',
    paired: 'Appareil associé. La synchronisation est lancée.',
    reportTitle: 'Rapport',
    reportHelp: 'Cette semaine comparée aux sept jours d’avant. Le suivi reste local.',
    thisWeek: 'Cette semaine',
    prevWeek: 'Semaine d’avant',
    delta: 'Écart',
    apps: 'Applications',
    empty: 'Pas encore assez de jours pour comparer.',
    loading: 'Chargement'
  },
  en: {
    sync: 'Sync',
    report: 'Report',
    syncTitle: 'Sync',
    syncHelp:
      'Off until you turn it on. Turning it on creates an anonymous account and a 32-byte key that stays on this computer. You do not type a token or a passphrase. The server stores only ciphertext. The default server is https://sync.unscroll.elimbi.com. Change the address to use your own.',
    url: 'Server URL',
    token: 'Token',
    passphrase: 'Passphrase',
    tokenKeep: 'Leave blank to keep the saved token or key.',
    enable: 'Enable sync',
    clear: 'Clear the saved token',
    clearPassphrase: 'Clear the saved key or passphrase',
    save: 'Save',
    push: 'Sync now',
    pull: 'Receive snapshot',
    https: 'HTTPS checks the certificate. The default server is https://sync.unscroll.elimbi.com. On this machine, http://127.0.0.1 is fine.',
    ready: 'A key or passphrase is already saved on this computer.',
    advanced: 'Advanced',
    advancedHelp:
      'For an existing vault, or a passphrase you already use. Leave blank to keep what is saved. A new account does not use these fields.',
    saved: 'Saved. Nothing is sent until you ask.',
    provisioned: 'Account created. Sync started. The key stays on this computer.',
    accountSaved: 'The account is saved on this computer. Sync did not finish.',
    pushed: 'Snapshot sent.',
    pulled: 'Snapshot received.',
    unchanged: 'No snapshot on the server.',
    pair: 'Pair a device',
    hideQr: 'Hide QR code',
    pairWarning:
      'This QR code and this link grant full vault access (address, token and encryption key). Treat them like a password.',
    qrAlt: 'Pairing QR code',
    link: 'Pairing link',
    copyLink: 'Copy link',
    copied: 'Link copied.',
    copyFailed: 'Copy failed. Select the link.',
    qrTooBig: 'The QR code is too long. Copy the link.',
    paste: 'Paste a link',
    pasteHelp: 'The link saves the account and starts sync.',
    pasteAction: 'Pair',
    pairNeedSave: 'Turn sync on first.',
    paired: 'Device paired. Sync started.',
    reportTitle: 'Report',
    reportHelp: 'This week against the seven days before it. Tracking stays local.',
    thisWeek: 'This week',
    prevWeek: 'Previous week',
    delta: 'Change',
    apps: 'Applications',
    empty: 'Not enough days to compare yet.',
    loading: 'Loading'
  },
  es: {
    sync: 'Sync',
    report: 'Informe',
    syncTitle: 'Sincronización',
    syncHelp:
      'Desactivada hasta que la active. Al activarla se crea una cuenta anónima y una clave de 32 bytes que se queda en este equipo. No tiene que escribir un token ni una frase. El servidor solo guarda el cifrado. La dirección por defecto es https://sync.unscroll.elimbi.com. Cámbiela para usar un servidor propio.',
    url: 'URL del servidor',
    token: 'Token',
    passphrase: 'Frase secreta',
    tokenKeep: 'Déjelo vacío para conservar el token o la clave ya guardados.',
    enable: 'Activar la sincronización',
    clear: 'Borrar el token guardado',
    clearPassphrase: 'Borrar la clave o la frase guardada',
    save: 'Guardar',
    push: 'Enviar la instantánea',
    pull: 'Recibir la instantánea',
    https: 'HTTPS comprueba el certificado. La dirección por defecto es https://sync.unscroll.elimbi.com. En esta máquina, http://127.0.0.1 sigue siendo posible.',
    ready: 'Ya hay una clave o una frase guardada en este equipo.',
    advanced: 'Avanzado',
    advancedHelp:
      'Para un cofre ya creado o una frase secreta antigua. Déjelo vacío para conservar lo guardado. Una cuenta nueva no usa estos campos.',
    saved: 'Guardado. No hay envío hasta que usted lo pida.',
    provisioned: 'Cuenta creada. La sincronización ha empezado. La clave se queda en este equipo.',
    accountSaved: 'La cuenta quedó guardada en este equipo. La sincronización no terminó.',
    pushed: 'Instantánea enviada.',
    pulled: 'Instantánea recibida.',
    unchanged: 'No hay instantánea en el servidor.',
    pair: 'Asociar un dispositivo',
    hideQr: 'Ocultar el QR',
    pairWarning:
      'Este QR y este enlace dan acceso completo al cofre (dirección, token y clave de cifrado). Trátelos como una contraseña.',
    qrAlt: 'QR de asociación',
    link: 'Enlace de asociación',
    copyLink: 'Copiar el enlace',
    copied: 'Enlace copiado.',
    copyFailed: 'La copia falló. Seleccione el enlace.',
    qrTooBig: 'El QR es demasiado largo. Copie el enlace.',
    paste: 'Pegar un enlace',
    pasteHelp: 'El enlace guarda la cuenta e inicia la sincronización.',
    pasteAction: 'Asociar',
    pairNeedSave: 'Active primero la sincronización.',
    paired: 'Dispositivo asociado. La sincronización está iniciada.',
    reportTitle: 'Informe',
    reportHelp: 'Esta semana frente a los siete días anteriores. El seguimiento sigue en local.',
    thisWeek: 'Esta semana',
    prevWeek: 'Semana anterior',
    delta: 'Diferencia',
    apps: 'Aplicaciones',
    empty: 'Aún no hay días bastantes para comparar.',
    loading: 'Cargando'
  },
  de: {
    sync: 'Sync',
    report: 'Bericht',
    syncTitle: 'Synchronisation',
    syncHelp:
      'Aus, bis Sie sie einschalten. Beim Einschalten entstehen ein anonymes Konto und ein 32-Byte-Schlüssel, der auf diesem Computer bleibt. Sie tippen weder Token noch Phrase. Der Server speichert nur Geheimtext. Die Vorgabe ist https://sync.unscroll.elimbi.com. Ändern Sie die Adresse für einen eigenen Server.',
    url: 'Server-URL',
    token: 'Token',
    passphrase: 'Geheime Phrase',
    tokenKeep: 'Leer lassen, um das gespeicherte Token oder den Schlüssel zu behalten.',
    enable: 'Synchronisation einschalten',
    clear: 'Gespeichertes Token löschen',
    clearPassphrase: 'Gespeicherten Schlüssel oder Phrase löschen',
    save: 'Speichern',
    push: 'Momentaufnahme senden',
    pull: 'Momentaufnahme empfangen',
    https: 'HTTPS prüft das Zertifikat. Die Vorgabe ist https://sync.unscroll.elimbi.com. Auf diesem Rechner bleibt http://127.0.0.1 möglich.',
    ready: 'Ein Schlüssel oder eine Phrase ist auf diesem Computer schon gespeichert.',
    advanced: 'Erweitert',
    advancedHelp:
      'Für einen bestehenden Tresor oder eine ältere Phrase. Leer lassen, um Gespeichertes zu behalten. Ein neues Konto braucht diese Felder nicht.',
    saved: 'Gespeichert. Nichts geht hinaus, bis Sie es verlangen.',
    provisioned: 'Konto erstellt. Die Synchronisation wurde gestartet. Der Schlüssel bleibt auf diesem Computer.',
    accountSaved: 'Das Konto ist auf diesem Computer gespeichert. Die Synchronisation ist nicht fertig geworden.',
    pushed: 'Momentaufnahme gesendet.',
    pulled: 'Momentaufnahme empfangen.',
    unchanged: 'Keine Momentaufnahme auf dem Server.',
    pair: 'Ein Gerät koppeln',
    hideQr: 'QR-Code ausblenden',
    pairWarning:
      'Dieser QR-Code und dieser Link geben vollen Zugriff auf den Tresor (Adresse, Token und Schlüssel). Behandeln Sie beides wie ein Passwort.',
    qrAlt: 'Kopplungs-QR-Code',
    link: 'Kopplungslink',
    copyLink: 'Link kopieren',
    copied: 'Link kopiert.',
    copyFailed: 'Kopieren ist fehlgeschlagen. Markieren Sie den Link.',
    qrTooBig: 'Der QR-Code ist zu lang. Kopieren Sie den Link.',
    paste: 'Link einfügen',
    pasteHelp: 'Der Link speichert das Konto und startet die Synchronisation.',
    pasteAction: 'Koppeln',
    pairNeedSave: 'Schalten Sie zuerst die Synchronisation ein.',
    paired: 'Gerät gekoppelt. Die Synchronisation wurde gestartet.',
    reportTitle: 'Bericht',
    reportHelp: 'Diese Woche gegen die sieben Tage davor. Die Erfassung bleibt lokal.',
    thisWeek: 'Diese Woche',
    prevWeek: 'Vorwoche',
    delta: 'Abstand',
    apps: 'Anwendungen',
    empty: 'Noch nicht genug Tage für einen Vergleich.',
    loading: 'Laden'
  },
  pt: {
    sync: 'Sync',
    report: 'Relatório',
    syncTitle: 'Sincronização',
    syncHelp:
      'Desligada até a ativar. Ao ativar, cria-se uma conta anónima e uma chave de 32 bytes que fica neste computador. Não escreve um token nem uma frase. O servidor só guarda o cifrado. O endereço predefinido é https://sync.unscroll.elimbi.com. Mude-o para usar um servidor seu.',
    url: 'URL do servidor',
    token: 'Token',
    passphrase: 'Frase secreta',
    tokenKeep: 'Deixe vazio para manter o token ou a chave já guardados.',
    enable: 'Ativar a sincronização',
    clear: 'Apagar o token guardado',
    clearPassphrase: 'Apagar a chave ou a frase guardada',
    save: 'Guardar',
    push: 'Enviar o instantâneo',
    pull: 'Receber o instantâneo',
    https: 'HTTPS verifica o certificado. O endereço predefinido é https://sync.unscroll.elimbi.com. Nesta máquina, http://127.0.0.1 continua possível.',
    ready: 'Já há uma chave ou uma frase guardada neste computador.',
    advanced: 'Avançado',
    advancedHelp:
      'Para um cofre já criado ou uma frase secreta antiga. Deixe vazio para manter o que está guardado. Uma conta nova não usa estes campos.',
    saved: 'Guardado. Nada é enviado até você o pedir.',
    provisioned: 'Conta criada. A sincronização começou. A chave fica neste computador.',
    accountSaved: 'A conta ficou guardada neste computador. A sincronização não terminou.',
    pushed: 'Instantâneo enviado.',
    pulled: 'Instantâneo recebido.',
    unchanged: 'Nenhum instantâneo no servidor.',
    pair: 'Associar um aparelho',
    hideQr: 'Esconder o QR',
    pairWarning:
      'Este QR e esta ligação dão acesso completo ao cofre (endereço, token e chave de cifra). Trate-os como uma palavra-passe.',
    qrAlt: 'QR de associação',
    link: 'Ligação de associação',
    copyLink: 'Copiar a ligação',
    copied: 'Ligação copiada.',
    copyFailed: 'A cópia falhou. Selecione a ligação.',
    qrTooBig: 'O QR é demasiado longo. Copie a ligação.',
    paste: 'Colar uma ligação',
    pasteHelp: 'A ligação guarda a conta e inicia a sincronização.',
    pasteAction: 'Associar',
    pairNeedSave: 'Ative primeiro a sincronização.',
    paired: 'Aparelho associado. A sincronização foi iniciada.',
    reportTitle: 'Relatório',
    reportHelp: 'Esta semana face aos sete dias anteriores. O registo continua local.',
    thisWeek: 'Esta semana',
    prevWeek: 'Semana anterior',
    delta: 'Diferença',
    apps: 'Aplicações',
    empty: 'Ainda não há dias que cheguem para comparar.',
    loading: 'A carregar'
  }
}

function text(locale: Locale): ProCopy {
  return copy[locale]
}

const KNOWN_REASONS = ['disabled', 'invalid', 'unavailable', 'decrypt', 'schema', 'conflict', 'timeout', 'too-large', 'network'] as const
type KnownReason = (typeof KNOWN_REASONS)[number]

const reasonCopy: Record<Locale, Record<KnownReason | 'http' | 'other', string>> = {
  fr: {
    disabled: 'La sync est désactivée.',
    invalid: 'Indiquez une URL http(s) sans identifiant ni requête. Un jeton manuel compte 8 à 512 caractères. Une phrase compte 8 à 200 caractères.',
    unavailable: 'Cette édition ne synchronise pas.',
    decrypt: 'La clé ou la phrase secrète ne déchiffre pas l’instantané.',
    schema: 'L’instantané reçu n’est pas reconnu.',
    conflict: 'Le serveur a une révision de réglages plus récente, et cet ordinateur a aussi des changements. Rien n’a été écrasé.',
    timeout: 'Le serveur n’a pas répondu.',
    'too-large': 'L’instantané dépasse la taille admise.',
    network: 'La connexion a échoué.',
    http: 'Le serveur a répondu {code}.',
    other: 'La sync n’a pas abouti.'
  },
  en: {
    disabled: 'Sync is off.',
    invalid: 'Use an http(s) URL with no user info and no query. A manual token is 8 to 512 characters. A passphrase is 8 to 200 characters.',
    unavailable: 'This edition does not sync.',
    decrypt: 'The key or passphrase cannot decrypt the snapshot.',
    schema: 'The snapshot is not a version this app reads.',
    conflict: 'The server has a newer settings revision, and this computer also has changes. Nothing was overwritten.',
    timeout: 'The server did not answer.',
    'too-large': 'The snapshot is too large.',
    network: 'The connection failed.',
    http: 'The server answered {code}.',
    other: 'Sync did not finish.'
  },
  es: {
    disabled: 'La sincronización está desactivada.',
    invalid: 'Indique una URL http(s) sin usuario ni consulta. Un token manual tiene de 8 a 512 caracteres. Una frase tiene de 8 a 200.',
    unavailable: 'Esta edición no sincroniza.',
    decrypt: 'La clave o la frase secreta no descifra la instantánea.',
    schema: 'La instantánea recibida no se reconoce.',
    conflict: 'El servidor tiene ajustes más recientes y este equipo también cambió. No se ha sobrescrito nada.',
    timeout: 'El servidor no respondió.',
    'too-large': 'La instantánea supera el tamaño admitido.',
    network: 'La conexión falló.',
    http: 'El servidor respondió {code}.',
    other: 'La sincronización no terminó.'
  },
  de: {
    disabled: 'Die Synchronisation ist aus.',
    invalid: 'Geben Sie eine http(s)-URL ohne Nutzerdaten und ohne Abfrage an. Ein manuelles Token hat 8 bis 512 Zeichen. Eine Phrase hat 8 bis 200.',
    unavailable: 'Diese Ausgabe synchronisiert nicht.',
    decrypt: 'Der Schlüssel oder die Phrase entschlüsselt die Momentaufnahme nicht.',
    schema: 'Die empfangene Momentaufnahme ist unbekannt.',
    conflict: 'Der Server hat neuere Einstellungen, und dieser Computer hat ebenfalls Änderungen. Nichts wurde überschrieben.',
    timeout: 'Der Server hat nicht geantwortet.',
    'too-large': 'Die Momentaufnahme ist zu groß.',
    network: 'Die Verbindung ist fehlgeschlagen.',
    http: 'Der Server antwortete {code}.',
    other: 'Die Synchronisation ist nicht fertig geworden.'
  },
  pt: {
    disabled: 'A sincronização está desligada.',
    invalid: 'Indique um URL http(s) sem credenciais nem consulta. Um token manual tem 8 a 512 caracteres. Uma frase tem 8 a 200.',
    unavailable: 'Esta edição não sincroniza.',
    decrypt: 'A chave ou a frase secreta não decifra o instantâneo.',
    schema: 'O instantâneo recebido não é reconhecido.',
    conflict: 'O servidor tem definições mais recentes e este computador também mudou. Nada foi substituído.',
    timeout: 'O servidor não respondeu.',
    'too-large': 'O instantâneo excede o tamanho admitido.',
    network: 'A ligação falhou.',
    http: 'O servidor respondeu {code}.',
    other: 'A sincronização não terminou.'
  }
}

function reasonText(locale: Locale, reason: string): string {
  const pack = reasonCopy[locale]
  if (reason.startsWith('http-')) return pack.http.replaceAll('{code}', reason.slice(5))
  if ((KNOWN_REASONS as readonly string[]).includes(reason)) return pack[reason as KnownReason]
  return pack.other
}

function signed(locale: Locale, ms: number): string {
  if (ms === 0) return formatDuration(0, locale)
  const sign = ms > 0 ? '+' : '−'
  return `${sign}${formatDuration(Math.abs(ms), locale)}`
}

export function editionNav(locale: Locale): { id: EditionPageId; label: string }[] {
  const labels = text(locale)
  return [
    { id: 'sync', label: labels.sync },
    { id: 'report', label: labels.report }
  ]
}

export function EditionPage({ page, snap }: { page: string; snap: Snapshot }) {
  if (page === 'sync') return <SyncPage snap={snap} />
  if (page === 'report') return <ReportPage snap={snap} />
  return null
}

async function qrDataUrl(link: string): Promise<string | null> {
  const color = { dark: '#142226', light: '#ffffff' }
  try {
    return await toDataURL(link, { errorCorrectionLevel: 'M', margin: 2, width: 280, color })
  } catch {
    try {
      return await toDataURL(link, { errorCorrectionLevel: 'L', margin: 2, width: 280, color })
    } catch {
      return null
    }
  }
}

function SyncPage({ snap }: { snap: Snapshot }) {
  const locale = snap.settings.locale
  const labels = text(locale)
  const mounted = useRef(true)
  const [state, setState] = useState<SyncPublicState | null>(null)
  const [baseUrl, setBaseUrl] = useState(DEFAULT_SYNC_URL)
  const [token, setToken] = useState('')
  const [passphrase, setPassphrase] = useState('')
  const [enabled, setEnabled] = useState(false)
  const [clearToken, setClearToken] = useState(false)
  const [clearPassphrase, setClearPassphrase] = useState(false)
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [pairing, setPairing] = useState<{ link: string; image: string | null } | null>(null)
  const [paste, setPaste] = useState('')

  useEffect(() => {
    mounted.current = true
    let alive = true
    void window.unscroll.getSyncState().then((next) => {
      if (!alive) return
      setState(next)
      setBaseUrl(next.baseUrl || DEFAULT_SYNC_URL)
      setEnabled(next.enabled)
    })
    return () => {
      alive = false
      mounted.current = false
    }
  }, [])

  function onSave(event: FormEvent) {
    event.preventDefault()
    const typing = (!clearPassphrase && passphrase.trim().length > 0) || (!clearToken && token.trim().length > 0)
    const hasToken = !clearToken && Boolean(state?.tokenSet)
    const provisioning = enabled && !typing && !hasToken
    setBusy(true)
    void window.unscroll.saveSync({ enabled, baseUrl, token, passphrase, clearToken, clearPassphrase }).then((result) => {
      if (!mounted.current) return
      setBusy(false)
      if (!result.ok) {
        setError(reasonText(locale, result.reason))
        setNote('')
        return
      }
      setState(result.state)
      setEnabled(result.state.enabled)
      setBaseUrl(result.state.baseUrl || DEFAULT_SYNC_URL)
      setToken('')
      setPassphrase('')
      setClearToken(false)
      setClearPassphrase(false)
      setPairing(null)
      setError('')
      if (provisioning && result.state.lastError) setNote(labels.accountSaved)
      else setNote(provisioning && result.state.enabled ? labels.provisioned : labels.saved)
    })
  }

  function run(kind: 'push' | 'pull') {
    setBusy(true)
    const action = kind === 'push' ? window.unscroll.pushSync() : window.unscroll.pullSync()
    void action.then((result) => {
      if (!mounted.current) return
      setBusy(false)
      void window.unscroll.getSyncState().then((next) => {
        if (mounted.current) setState(next)
      })
      if (!result.ok) {
        setError(reasonText(locale, result.reason))
        setNote('')
        return
      }
      setError('')
      if (kind === 'push') setNote(labels.pushed)
      else setNote(result.changed ? labels.pulled : labels.unchanged)
    })
  }

  function onTogglePair() {
    if (pairing) {
      setPairing(null)
      return
    }
    setBusy(true)
    void window.unscroll
      .getPairingLink()
      .then(async (result) => {
        if (!mounted.current) return
        if (!result.ok) {
          setBusy(false)
          setPairing(null)
          setNote('')
          setError(result.reason === 'invalid' ? labels.pairNeedSave : reasonText(locale, result.reason))
          return
        }
        const image = await qrDataUrl(result.link)
        if (!mounted.current) return
        setBusy(false)
        setError('')
        setPairing({ link: result.link, image })
      })
      .catch(() => {
        if (!mounted.current) return
        setBusy(false)
        setError(reasonText(locale, 'network'))
        setNote('')
      })
  }

  function onCopy() {
    if (!pairing) return
    const field = document.getElementById('pairing-link')
    if (field instanceof HTMLInputElement) field.select()
    const write = navigator.clipboard?.writeText
    if (!write) {
      setNote('')
      setError(labels.copyFailed)
      return
    }
    void write.call(navigator.clipboard, pairing.link).then(
      () => {
        if (!mounted.current) return
        setError('')
        setNote(labels.copied)
      },
      () => {
        if (!mounted.current) return
        setNote('')
        setError(labels.copyFailed)
      }
    )
  }

  function onPaste(event: FormEvent) {
    event.preventDefault()
    const link = paste.trim()
    if (!link) return
    setBusy(true)
    void window.unscroll
      .applyPairingLink(link)
      .then((result) => {
        if (!mounted.current) return
        setBusy(false)
        if (result.state) {
          setState(result.state)
          setEnabled(result.state.enabled)
          setBaseUrl(result.state.baseUrl || DEFAULT_SYNC_URL)
          setToken('')
          setPassphrase('')
          setClearToken(false)
          setClearPassphrase(false)
        }
        if (!result.ok) {
          setNote('')
          setError(reasonText(locale, result.reason))
          return
        }
        setPaste('')
        setPairing(null)
        setError('')
        setNote(labels.paired)
      })
      .catch(() => {
        if (!mounted.current) return
        setBusy(false)
        setNote('')
        setError(reasonText(locale, 'network'))
      })
  }

  return (
    <>
      <header className="page-head">
        <h2>{labels.syncTitle}</h2>
      </header>
      <p className="help">{labels.syncHelp}</p>
      <div className="stack">
        <form className="card settings" onSubmit={onSave} aria-busy={busy}>
          <label className="checks">
            <input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />
            {labels.enable}
          </label>
          <label className="field">
            {labels.url}
            <input
              value={baseUrl}
              onChange={(event) => setBaseUrl(event.target.value)}
              inputMode="url"
              autoComplete="off"
              spellCheck={false}
              placeholder={DEFAULT_SYNC_URL}
              aria-invalid={error ? true : undefined}
            />
          </label>
          <p className="muted">{state?.passphraseSet ? labels.ready : labels.https}</p>
          <details className="advanced">
            <summary>{labels.advanced}</summary>
            <div className="settings">
              <label className="field">
                {labels.token}
                <input
                  type="password"
                  value={token}
                  onChange={(event) => setToken(event.target.value)}
                  autoComplete="off"
                  spellCheck={false}
                  maxLength={512}
                />
              </label>
              <label className="field">
                {labels.passphrase}
                <input
                  type="password"
                  value={passphrase}
                  onChange={(event) => setPassphrase(event.target.value)}
                  autoComplete="off"
                  spellCheck={false}
                  maxLength={200}
                />
              </label>
              <p className="muted">{state?.tokenSet || state?.passphraseSet ? labels.tokenKeep : labels.advancedHelp}</p>
              <label className="checks">
                <input type="checkbox" checked={clearToken} onChange={(event) => setClearToken(event.target.checked)} />
                {labels.clear}
              </label>
              <label className="checks">
                <input
                  type="checkbox"
                  checked={clearPassphrase}
                  onChange={(event) => setClearPassphrase(event.target.checked)}
                />
                {labels.clearPassphrase}
              </label>
            </div>
          </details>
          <div className="inline">
            <button className="primary" type="submit" disabled={busy}>
              {labels.save}
            </button>
            <button className="ghost" type="button" disabled={busy || !state?.enabled} onClick={() => run('push')}>
              {labels.push}
            </button>
          </div>
        </form>
        <section className="card settings">
          <div className="inline">
            <button className="ghost" type="button" disabled={busy} onClick={onTogglePair}>
              {pairing ? labels.hideQr : labels.pair}
            </button>
          </div>
          {pairing ? (
            <div className="pair-panel">
              <p className="pair-warning">{labels.pairWarning}</p>
              {pairing.image ? (
                <img className="pair-qr" src={pairing.image} alt={labels.qrAlt} />
              ) : (
                <p className="muted">{labels.qrTooBig}</p>
              )}
              <label className="field">
                {labels.link}
                <input
                  id="pairing-link"
                  className="pair-link"
                  readOnly
                  value={pairing.link}
                  spellCheck={false}
                  autoComplete="off"
                  onFocus={(event) => event.currentTarget.select()}
                />
              </label>
              <button className="ghost" type="button" onClick={onCopy}>
                {labels.copyLink}
              </button>
            </div>
          ) : null}
          <form className="settings" onSubmit={onPaste}>
            <label className="field">
              {labels.paste}
              <input
                value={paste}
                onChange={(event) => setPaste(event.target.value)}
                autoComplete="off"
                spellCheck={false}
                maxLength={8192}
                placeholder="unscroll://pair?…"
              />
            </label>
            <p className="muted">{labels.pasteHelp}</p>
            <button className="primary" type="submit" disabled={busy || paste.trim().length === 0}>
              {labels.pasteAction}
            </button>
          </form>
        </section>
        {error ? (
          <p className="error" role="alert">
            {error}
          </p>
        ) : null}
        {note ? (
          <p className="note" role="status">
            {note}
          </p>
        ) : null}
        {state?.lastPushAt ? <p className="muted">{state.lastPushAt}</p> : null}
        {state?.lastError ? (
          <p className="error" role="alert">
            {reasonText(locale, state.lastError)}
          </p>
        ) : null}
      </div>
    </>
  )
}

function ReportPage({ snap }: { snap: Snapshot }) {
  const locale = snap.settings.locale
  const labels = text(locale)
  const [report, setReport] = useState<AdvancedReport | null>(null)
  const [ready, setReady] = useState(false)
  useEffect(() => {
    let alive = true
    void window.unscroll.getReport().then((next) => {
      if (!alive) return
      setReport(next && next.kind === 'advanced-report-v1' ? next : null)
      setReady(true)
    })
    return () => {
      alive = false
    }
  }, [snap.todayTotalMs, snap.day])

  const max = report ? Math.max(1, ...report.days.map((day) => Math.max(day.thisMs, day.prevMs))) : 1
  return (
    <>
      <header className="page-head">
        <h2>{labels.reportTitle}</h2>
      </header>
      <p className="help">{labels.reportHelp}</p>
      {!ready ? (
        <EmptyState title={labels.loading} />
      ) : !report ? (
        <EmptyState title={labels.empty} />
      ) : (
        <div className="stack">
          <section className="card settings">
            <p>
              {labels.thisWeek} · <strong>{formatDuration(report.thisWeekMs, locale)}</strong>
            </p>
            <p>
              {labels.prevWeek} · <strong>{formatDuration(report.prevWeekMs, locale)}</strong>
            </p>
            <p>
              {labels.delta} · <strong>{signed(locale, report.deltaMs)}</strong>
            </p>
          </section>
          <section className="card">
            <ul className="app-list">
              {report.days.map((day) => (
                <li key={day.day} className="compare">
                  <span>{formatDayLabel(day.day, locale)}</span>
                  <div>
                    <Meter
                      kind="meter"
                      ratio={day.thisMs / max}
                      label={`${labels.thisWeek} · ${formatDayLabel(day.day, locale)} · ${formatDuration(day.thisMs, locale)}`}
                    />
                    <Meter
                      kind="meter"
                      ratio={day.prevMs / max}
                      color="var(--sand)"
                      label={`${labels.prevWeek} · ${formatDayLabel(day.day, locale)} · ${formatDuration(day.prevMs, locale)}`}
                    />
                  </div>
                  <strong>{formatDuration(day.thisMs, locale)}</strong>
                </li>
              ))}
            </ul>
          </section>
          <section className="card">
            <h2>{labels.apps}</h2>
            {report.apps.length === 0 ? (
              <EmptyState title={labels.empty} />
            ) : (
              <ul className="app-list">
                {report.apps.map((appRow) => (
                  <li key={appRow.appName}>
                    <div className="app-meta">
                      <span>{appRow.appName}</span>
                      <strong>{signed(locale, appRow.deltaMs)}</strong>
                    </div>
                    <span className="muted">
                      {formatDuration(appRow.thisMs, locale)} · {formatDuration(appRow.prevMs, locale)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </>
  )
}
