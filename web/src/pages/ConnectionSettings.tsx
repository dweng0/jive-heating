import { useNavigate } from 'react-router-dom'
import { ConnectionSetup } from '@/components/heating/ConnectionSetup'

/** Settings > Connection: the setup steps again, to change the broker or Zigbee2MQTT. Admins only. */
export function ConnectionSettingsPage() {
  const navigate = useNavigate()
  return <ConnectionSetup onFinish={() => navigate('/')} />
}
