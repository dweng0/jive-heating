import { useEffect, useState } from 'react'
import { Download, Smartphone } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { api } from '@/lib/api'

/** Download link for the Android app, with the three steps a sideload needs. Hidden when the server has no APK. */
export function AndroidAppCard() {
  const [app, setApp] = useState<{ size: number; builtAt: string } | null>(null)
  useEffect(() => {
    api.apps().then((a) => setApp(a.android), () => undefined)
  }, [])
  useEffect(() => {
    if (app && location.hash === '#android-app') document.getElementById('android-app')?.scrollIntoView({ behavior: 'smooth' })
  }, [app])
  if (!app) return null

  return (
    <Card id="android-app">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Smartphone className="size-5 text-muted-foreground" /> Android app
        </CardTitle>
        <CardDescription>
          {(app.size / 1_000_000).toFixed(0)} MB · built {new Date(app.builtAt).toLocaleDateString()}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <ol className="list-decimal space-y-1.5 pl-5 text-sm text-muted-foreground">
          <li>On the Android phone, open this page and tap <span className="font-medium text-foreground">Download</span>.</li>
          <li>Open the downloaded <span className="font-medium text-foreground">heating.apk</span>. If asked, allow your browser to install apps.</li>
          <li>
            Open <span className="font-medium text-foreground">Heating</span>, enter <span className="font-medium text-foreground">{location.origin}</span>, and sign in
            with the same username and password.
          </li>
        </ol>
        <Button asChild size="lg" className="h-11 self-start">
          <a href="/api/app/android" download="heating.apk">
            <Download /> Download for Android
          </a>
        </Button>
      </CardContent>
    </Card>
  )
}
