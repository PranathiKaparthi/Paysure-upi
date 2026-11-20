import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { ReliabilityScore, ScoreSnapshot } from '../api/types'

function tone(score?: number | null): string {
  if (score === undefined || score === null) return 'text-slate-400'
  return score >= 90 ? 'text-emerald-600' : score >= 70 ? 'text-amber-600' : 'text-rose-600'
}

/** Explainable reliability score: every component shows its value, weight and how it was scored. */
export function ScoreCard({ score, history }: { score: ReliabilityScore | null; history: ScoreSnapshot[] }) {
  if (!score) return <div className="card text-sm text-slate-500">Loading reliability score…</div>
  const chart = history.map((h) => ({
    t: new Date(h.capturedAt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
    score: h.score,
    trigger: h.trigger,
  }))
  return (
    <section className="card space-y-4" aria-label="Reliability score">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="font-semibold">Payment reliability score <span className="text-xs font-normal text-slate-500">(demo)</span></h2>
          <p className="max-w-xl text-xs text-slate-500">{score.disclaimer}</p>
        </div>
        <div className="text-right">
          <div className={`text-5xl font-extrabold tabular-nums ${tone(score.score)}`} data-testid="score-value">
            {score.score ?? '—'}
          </div>
          <div className="text-xs text-slate-500">{score.score === undefined || score.score === null ? 'no data yet' : 'out of 100'}</div>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="text-xs uppercase text-slate-500">
            <tr><th className="py-2">Component</th><th>Weight</th><th>Measured</th><th className="w-40">Score</th></tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {score.components.map((c) => (
              <tr key={c.key} title={c.explanation} className={c.available ? '' : 'text-slate-400'}>
                <td className="py-2 pr-3 font-medium">{c.label}</td>
                <td className="pr-3 tabular-nums">{c.weight}</td>
                <td className="pr-3">{c.valueText}</td>
                <td>
                  {c.available && c.score !== undefined ? (
                    <div className="flex items-center gap-2">
                      <div className="h-2 flex-1 rounded-full bg-slate-100">
                        <div className={`h-2 rounded-full ${c.score >= 90 ? 'bg-emerald-500' : c.score >= 70 ? 'bg-amber-500' : 'bg-rose-500'}`}
                          style={{ width: `${c.score}%` }} />
                      </div>
                      <span className="w-8 text-right tabular-nums">{c.score}</span>
                    </div>
                  ) : (
                    <span className="text-xs">excluded</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-slate-500">{score.methodology}</p>

      {chart.length >= 2 && (
        <div className="h-48" aria-label="Score history">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chart} margin={{ top: 8, right: 8, bottom: 0, left: -20 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
              <XAxis dataKey="t" tick={{ fontSize: 11 }} />
              <YAxis domain={[0, 100]} tick={{ fontSize: 11 }} />
              <Tooltip />
              <Line type="monotone" dataKey="score" stroke="#2453e0" strokeWidth={2} dot={{ r: 3 }} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  )
}
