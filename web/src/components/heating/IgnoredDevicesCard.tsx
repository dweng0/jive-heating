import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { energyApi } from '@/lib/energy'
import { useHome } from '@/lib/home'

/** Devices an admin chose to leave alone, each with "Show again". Admins only; hidden when none are ignored. */
export function IgnoredDevicesCard() {
  const { run } = useHome()
  const [ignored, setIgnored] = useState<string[]>([])

  useEffect(() => {
    energyApi.ignoredDevices().then(setIgnored, () => undefined)
  }, [])

  async function showAgain(name: string) {
    if (await run(() => energyApi.showDeviceAgain(name), "Couldn't show the device again")) {
      setIgnored((names) => names.filter((n) => n !== name))
    }
  }

  if (ignored.length === 0) return null

  return (
    <Card data-testid="ignored-devices">
      <CardHeader>
        <CardTitle>Ignored devices</CardTitle>
        <CardDescription>Left out of “Waiting to be set up”.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-1">
        {ignored.map((name) => (
          <div key={name} className="flex items-center gap-3">
            <div className="flex-1 font-medium">{name}</div>
            <Button variant="ghost" aria-label={`Show ${name} again`} onClick={() => showAgain(name)}>
              Show again
            </Button>
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
