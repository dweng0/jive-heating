import { useEffect, useState, type FormEvent } from 'react'
import { PoundSterling, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NOT_CONVERTED, PRICE_FIELDS, currencyChoices, currencyLabel, energyApi, formatPrice, priceUnit, type EnergyPrices, type EnergySettings } from '@/lib/energy'
import { useHome } from '@/lib/home'

/** Gas and electricity prices and the household's currency for the energy dashboard. Admins only: the page leaves it out for members. */
export function EnergyPricesCard() {
  const { run } = useHome()
  const [settings, setSettings] = useState<EnergySettings | null>(null)
  const [draft, setDraft] = useState<Record<keyof EnergyPrices, string> | null>(null)
  const [currency, setCurrency] = useState('GBP')

  useEffect(() => {
    energyApi.settings().then(setSettings, () => undefined)
  }, [])

  function edit() {
    if (!settings) return
    setDraft(Object.fromEntries(PRICE_FIELDS.map((f) => [f.key, String(settings[f.key])])) as Record<keyof EnergyPrices, string>)
    setCurrency(settings.currency)
  }

  const parsed = draft && Object.fromEntries(PRICE_FIELDS.map((f) => [f.key, Number(draft[f.key])]))
  const valid =
    parsed != null &&
    PRICE_FIELDS.every((f) => draft![f.key].trim() !== '' && Number.isFinite(parsed[f.key]) && parsed[f.key] >= 0) &&
    parsed.boilerAverageKw > 0 &&
    parsed.boilerAverageKw <= 100

  async function save(e: FormEvent) {
    e.preventDefault()
    if (!valid || !parsed) return
    // Only a changed currency is sent: prices are never converted.
    const body = currency === settings?.currency ? parsed : { ...parsed, currency }
    const updated = await run(() => energyApi.savePrices(body as EnergyPrices), "Couldn't save the prices")
    if (updated) {
      setSettings(updated)
      setDraft(null)
    }
  }

  return (
    <Card data-testid="energy-prices">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <PoundSterling className="size-5 text-muted-foreground" />
          <h2>Energy prices</h2>
        </CardTitle>
        <CardDescription>Used for the costs on the dashboard. Changing a price recalculates past costs too.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {settings?.notices.map((n) => (
          <p key={n} className="flex items-center gap-2 text-sm text-heat-text">
            <TriangleAlert className="size-4" /> {n}
          </p>
        ))}
        {!settings && <p className="text-sm text-muted-foreground">Loading…</p>}
        {settings && !draft && (
          <>
            <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 text-sm">
              {PRICE_FIELDS.map((f) => (
                <div key={f.key} className="contents">
                  <dt className="text-muted-foreground">{f.label}</dt>
                  <dd className="text-right font-medium tabular-nums">{formatPrice(settings[f.key], f, settings.currency)}</dd>
                </div>
              ))}
            </dl>
            <p className="text-sm text-muted-foreground">Currency: {currencyLabel(settings.currency)}</p>
            <Button variant="outline" className="self-end" onClick={edit}>
              Edit prices
            </Button>
          </>
        )}
        {settings && draft && (
          <form onSubmit={save} className="flex flex-col gap-3">
            {PRICE_FIELDS.map((f) => (
              <div key={f.key} className="grid grid-cols-[1fr_8rem_5rem] items-center gap-2">
                <Label htmlFor={`price-${f.key}`}>{f.label}</Label>
                <Input
                  id={`price-${f.key}`}
                  inputMode="decimal"
                  value={draft[f.key]}
                  onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}
                  className="text-right tabular-nums"
                />
                <span className="text-sm text-muted-foreground">{priceUnit(f, currency)}</span>
              </div>
            ))}
            <div className="grid grid-cols-[1fr_13rem] items-center gap-2">
              <Label htmlFor="price-currency">Currency</Label>
              <select
                id="price-currency"
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                className="h-9 rounded-md border border-input bg-background px-3 text-sm"
              >
                {currencyChoices(settings.currency).map((c) => (
                  <option key={c.code} value={c.code}>
                    {c.label}
                  </option>
                ))}
              </select>
            </div>
            {currency !== settings.currency && (
              <p className="flex items-center gap-2 text-sm text-heat-text">
                <TriangleAlert className="size-4" /> {NOT_CONVERTED}
              </p>
            )}
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" onClick={() => setDraft(null)}>
                Cancel
              </Button>
              <Button type="submit" disabled={!valid}>
                Save
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  )
}
