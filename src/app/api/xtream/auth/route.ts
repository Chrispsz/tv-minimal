import { NextRequest, NextResponse } from 'next/server'

export async function POST(request: NextRequest) {
  try {
    const { host, username, password } = await request.json()

    if (!host || !username || !password) {
      return NextResponse.json({ error: 'Credenciais incompletas' }, { status: 400 })
    }

    // Formatar host
    const fullHost = host.includes(':') ? host : `${host}:8080`
    const serverUrl = `http://${fullHost}`

    // Autenticar via Xtream Codes API
    const authUrl = `${serverUrl}/player_api.php?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`

    console.log('[Xtream] Authenticating:', serverUrl)

    const response = await fetch(authUrl, {
      method: 'GET',
      headers: {
        'User-Agent': 'IPTVPlayer/1.0',
      },
      signal: AbortSignal.timeout(15000)
    })

    if (!response.ok) {
      return NextResponse.json({ 
        error: `Erro HTTP: ${response.status}`,
        status: 'error' 
      }, { status: response.status })
    }

    const data = await response.json()

    // Verificar se autenticou
    if (data.user_info?.auth === 1) {
      return NextResponse.json({
        status: 'connected',
        serverUrl,
        userInfo: {
          username: data.user_info.username,
          status: data.user_info.status,
          expDate: data.user_info.exp_date,
          isTrial: data.user_info.is_trial,
          activeCons: data.user_info.active_cons,
          maxConnections: data.user_info.max_connections,
          createdAt: data.user_info.created_at,
        },
        serverInfo: {
          url: serverUrl,
          port: data.server_info?.port,
          httpsPort: data.server_info?.https_port,
          serverName: data.server_info?.server_name,
          timezone: data.server_info?.timezone,
        }
      })
    } else {
      return NextResponse.json({
        status: 'failed',
        error: 'Autenticação falhou',
        details: data
      }, { status: 401 })
    }
  } catch (error: any) {
    console.error('[Xtream] Auth error:', error)
    return NextResponse.json({
      status: 'error',
      error: error.message || 'Erro de conexão'
    }, { status: 500 })
  }
}
