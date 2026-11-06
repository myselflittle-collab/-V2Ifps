import { ImageFormat, Settings, nf } from '../api'

interface Props {
  settings: Settings
  totalFrames: number
  disabled: boolean
  onChange: (s: Settings) => void
}

const FORMATS: { id: ImageFormat; name: string; note: string }[] = [
  { id: 'png', name: 'PNG', note: 'Lossless. Exact pixels, largest files.' },
  { id: 'jpeg', name: 'JPEG', note: 'Smallest files. Quality is adjustable.' },
  { id: 'webp', name: 'WebP', note: 'Lossless at 100, smaller below it.' },
]

export function SettingsPanel({ settings: s, totalFrames, disabled, onChange }: Props) {
  const set = (patch: Partial<Settings>) => onChange({ ...s, ...patch })
  const ranged = s.start_frame != null || s.end_frame != null
  const pad = Math.max(4, String(totalFrames || 1).length)
  const sample = s.zero_pad ? '1'.padStart(pad, '0') : '1'
  const ext = s.format === 'jpeg' ? 'jpg' : s.format

  return (
    <fieldset className="settings" disabled={disabled}>
      <div className="field">
        <span className="field__label" id="fmt-label">Image format</span>
        <div className="segmented" role="radiogroup" aria-labelledby="fmt-label">
          {FORMATS.map((f) => (
            <label key={f.id} className={`segmented__opt ${s.format === f.id ? 'is-on' : ''}`}>
              <input type="radio" name="fmt" value={f.id} checked={s.format === f.id}
                onChange={() => set({ format: f.id, quality: f.id === 'png' ? 100 : s.quality })} />
              <span className="segmented__name">{f.name}</span>
              <span className="segmented__note">{f.note}</span>
            </label>
          ))}
        </div>
      </div>

      {s.format !== 'png' && (
        <div className="field">
          <label className="field__label" htmlFor="q">Quality <span className="num accent">{s.quality}</span>
            {s.format === 'webp' && s.quality === 100 && <span className="muted"> (lossless)</span>}
          </label>
          <input id="q" type="range" min={50} max={100} value={s.quality}
            onChange={(e) => set({ quality: Number(e.target.value) })} />
        </div>
      )}

      <div className="field">
        <span className="field__label">File names</span>
        <label className="check">
          <input type="checkbox" checked={s.zero_pad} onChange={(e) => set({ zero_pad: e.target.checked })} />
          <span>Pad with zeros so files sort correctly everywhere</span>
        </label>
        <p className="field__example">
          <span className="mono">{sample}.{ext}</span>
          <span className="mono">{s.zero_pad ? '2'.padStart(pad, '0') : '2'}.{ext}</span>
          <span className="muted">…</span>
          <span className="mono">{totalFrames ? (s.zero_pad ? String(totalFrames).padStart(pad, '0') : totalFrames) : 'N'}.{ext}</span>
        </p>
      </div>

      <div className="field">
        <span className="field__label">Frames</span>
        <label className="check">
          <input type="radio" name="range" checked={!ranged} onChange={() => set({ start_frame: null, end_frame: null })} />
          <span>Every frame{totalFrames ? <> ({nf.format(totalFrames)})</> : null}</span>
        </label>
        <label className="check">
          <input type="radio" name="range" checked={ranged}
            onChange={() => set({ start_frame: 1, end_frame: totalFrames || null })} />
          <span>A range of frames</span>
        </label>
        {ranged && (
          <div className="range">
            <label>From frame
              <input type="number" min={1} max={totalFrames || undefined} value={s.start_frame ?? ''}
                onChange={(e) => set({ start_frame: e.target.value ? Math.max(1, Number(e.target.value)) : null })} />
            </label>
            <label>to frame
              <input type="number" min={1} max={totalFrames || undefined} value={s.end_frame ?? ''}
                onChange={(e) => set({ end_frame: e.target.value ? Math.max(1, Number(e.target.value)) : null })} />
            </label>
            <p className="muted small">The first frame of the range is saved as 1.{ext}.</p>
          </div>
        )}
      </div>
    </fieldset>
  )
}
