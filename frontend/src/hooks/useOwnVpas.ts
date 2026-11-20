import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Account } from '../api/types'

export interface OwnVpa {
  vpa: string
  accountName: string
  availablePaise: number
}

/** Active VPAs on the caller's active accounts — the only addresses they may pay from or request into. */
export function useOwnVpas() {
  const [accounts, setAccounts] = useState<Account[] | null>(null)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let cancelled = false
    api.listAccounts().then(
      (list) => {
        if (!cancelled) setAccounts(list)
      },
      () => {
        if (!cancelled) setAccounts([])
      },
    )
    return () => {
      cancelled = true
    }
  }, [version])

  const vpas: OwnVpa[] = (accounts ?? [])
    .filter((a) => a.status === 'ACTIVE')
    .flatMap((a) =>
      a.vpas.filter((v) => v.status === 'ACTIVE').map((v) => ({ vpa: v.vpa, accountName: a.displayName, availablePaise: a.availablePaise })),
    )
  return { vpas, loading: accounts === null, reload: () => setVersion((v) => v + 1) }
}
