'use client'

import { useState, useEffect, useCallback } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Badge } from '@/components/ui/badge'
import { 
  Tv, Play, Copy, Check, Search, Loader2,
  Wifi, Clock, Users, Server, ExternalLink,
  Smartphone, Monitor
} from 'lucide-react'

// Tipos
interface UserInfo {
  username: string
  status: string
  expDate: string
  isTrial: string
  activeCons: string
  maxConnections: string
  createdAt: string
}

interface Category {
  category_id: string
  category_name: string
  parent_id: number
}

interface Stream {
  streamId: string
  name: string
  icon: string
  categoryId: string
}

interface AuthResponse {
  status: string
  serverUrl: string
  userInfo?: UserInfo
  error?: string
}

// Detectar se é Android
const isAndroid = () => {
  if (typeof window === 'undefined') return false
  return /android/i.test(navigator.userAgent)
}

// Package names dos players
const PLAYERS = {
  FONGMI: 'com.fongmi.android.tv',
  VLC: 'org.videolan.vlc',
  MX_PLAYER: 'com.mxtech.videoplayer.ad',
  MX_PLAYER_PRO: 'com.mxtech.videoplayer.pro',
}

export default function IPTVPlayer() {
  // Estados de autenticação
  const [host, setHost] = useState('newultra.xyz:8080')
  const [username, setUsername] = useState('978363714tv')
  const [password, setPassword] = useState('0l4nsal')
  const [isConnecting, setIsConnecting] = useState(false)
  const [authData, setAuthData] = useState<AuthResponse | null>(null)
  
  // Estados de dados
  const [categories, setCategories] = useState<Category[]>([])
  const [streams, setStreams] = useState<Stream[]>([])
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  
  // Estados UI
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [copiedUrl, setCopiedUrl] = useState<string | null>(null)
  const [isAndroidDevice, setIsAndroidDevice] = useState(false)

  // Detectar dispositivo
  useEffect(() => {
    setIsAndroidDevice(isAndroid())
  }, [])

  // Gerar URL do stream
  const getStreamUrl = useCallback((streamId: string) => {
    const fullHost = host.includes(':') ? host : `${host}:8080`
    return `http://${fullHost}/live/${username}/${password}/${streamId}.m3u8`
  }, [host, username, password])

  // ============================================
  // INTENTS ANDROID
  // ============================================

  // Abrir com seletor de apps
  const openWithChooser = useCallback((stream: Stream) => {
    const url = getStreamUrl(stream.streamId)
    const intent = `intent://${encodeURIComponent(url)}#Intent;action=android.intent.action.VIEW;type=video/*;category=android.intent.category.BROWSABLE;end`
    window.location.href = intent
  }, [getStreamUrl])

  // Abrir no FongMi/TV
  const openInFongMi = useCallback((stream: Stream) => {
    const url = getStreamUrl(stream.streamId)
    const intent = `intent:${url}#Intent;action=android.intent.action.VIEW;type=video/*;package=${PLAYERS.FONGMI};end`
    window.location.href = intent
  }, [getStreamUrl])

  // Abrir no VLC
  const openInVLC = useCallback((stream: Stream) => {
    const url = getStreamUrl(stream.streamId)
    const intent = `intent:${url}#Intent;action=android.intent.action.VIEW;type=video/*;package=${PLAYERS.VLC};end`
    window.location.href = intent
  }, [getStreamUrl])

  // Abrir no MX Player
  const openInMXPlayer = useCallback((stream: Stream) => {
    const url = getStreamUrl(stream.streamId)
    const intent = `intent:${url}#Intent;action=android.intent.action.VIEW;type=video/*;package=${PLAYERS.MX_PLAYER};end`
    window.location.href = intent
  }, [getStreamUrl])

  // Abrir em nova aba (Desktop)
  const openInNewTab = useCallback((stream: Stream) => {
    const url = getStreamUrl(stream.streamId)
    window.open(url, '_blank')
  }, [getStreamUrl])

  // Copiar URL
  const copyUrl = useCallback((stream: Stream) => {
    const url = getStreamUrl(stream.streamId)
    navigator.clipboard.writeText(url)
    setCopiedUrl(stream.streamId)
    setTimeout(() => setCopiedUrl(null), 2000)
  }, [getStreamUrl])

  // ============================================
  // AUTH & DATA
  // ============================================

  const handleConnect = async () => {
    setIsConnecting(true)
    setError(null)
    
    try {
      const response = await fetch('/api/xtream/auth', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ host, username, password })
      })
      
      const data: AuthResponse = await response.json()
      
      if (data.status === 'connected') {
        setAuthData(data)
        loadCategories()
      } else {
        setError(data.error || 'Falha na autenticação')
      }
    } catch (err: any) {
      setError(err.message || 'Erro de conexão')
    } finally {
      setIsConnecting(false)
    }
  }

  const loadCategories = async () => {
    try {
      const response = await fetch('/api/xtream/categories', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ host, username, password, type: 'live' })
      })
      
      const data = await response.json()
      setCategories(data.categories || [])
    } catch (err) {
      console.error('Erro ao carregar categorias:', err)
    }
  }

  const loadStreams = async (categoryId: string) => {
    setIsLoading(true)
    setSelectedCategory(categoryId)
    
    try {
      const response = await fetch('/api/xtream/streams', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ host, username, password, type: 'live', categoryId })
      })
      
      const data = await response.json()
      setStreams(data.streams || [])
    } catch (err) {
      console.error('Erro ao carregar canais:', err)
    } finally {
      setIsLoading(false)
    }
  }

  // Filtrar canais
  const filteredStreams = searchQuery
    ? streams.filter(s => s.name.toLowerCase().includes(searchQuery.toLowerCase()))
    : streams

  // Formatar data
  const formatExpDate = (timestamp: string) => {
    if (!timestamp) return 'N/A'
    return new Date(parseInt(timestamp) * 1000).toLocaleDateString('pt-BR')
  }

  return (
    <div className="min-h-screen bg-slate-900 text-white">
      {/* Header */}
      <header className="fixed top-0 left-0 right-0 z-50 bg-slate-800/95 backdrop-blur border-b border-slate-700">
        <div className="container mx-auto px-4 py-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="h-10 w-10 rounded-lg bg-gradient-to-br from-purple-500 to-blue-600 flex items-center justify-center">
              <Tv className="h-5 w-5" />
            </div>
            <div>
              <h1 className="text-lg font-bold">IPLINKS</h1>
              <p className="text-xs text-slate-400 flex items-center gap-1">
                {authData?.status === 'connected' ? (
                  <>
                    <Wifi className="h-3 w-3 text-green-400" />
                    Conectado
                    {isAndroidDevice && <Smartphone className="h-3 w-3 ml-1" />}
                  </>
                ) : 'Aguardando conexão'}
              </p>
            </div>
          </div>
          
          {authData?.userInfo && (
            <div className="flex items-center gap-3 text-xs">
              <Badge variant="outline" className="border-green-500 text-green-400">
                <Users className="h-3 w-3 mr-1" />
                {authData.userInfo.activeCons}/{authData.userInfo.maxConnections}
              </Badge>
              <Badge variant="outline" className="border-blue-500 text-blue-400">
                <Clock className="h-3 w-3 mr-1" />
                {formatExpDate(authData.userInfo.expDate)}
              </Badge>
            </div>
          )}
        </div>
      </header>

      <main className="container mx-auto px-4 pt-20 pb-8">
        {/* Login Section */}
        {!authData && (
          <Card className="max-w-md mx-auto mt-8 bg-slate-800 border-slate-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Server className="h-5 w-5" />
                Conectar IPTV
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <label className="text-sm text-slate-400">Servidor</label>
                <Input
                  value={host}
                  onChange={(e) => setHost(e.target.value)}
                  placeholder="servidor.com:8080"
                  className="bg-slate-700 border-slate-600"
                />
              </div>
              <div className="space-y-2">
                <label className="text-sm text-slate-400">Usuário</label>
                <Input
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="seu_usuario"
                  className="bg-slate-700 border-slate-600"
                />
              </div>
              <div className="space-y-2">
                <label className="text-sm text-slate-400">Senha</label>
                <Input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="sua_senha"
                  className="bg-slate-700 border-slate-600"
                />
              </div>
              
              {error && (
                <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/50 text-red-400 text-sm">
                  {error}
                </div>
              )}
              
              <Button
                onClick={handleConnect}
                disabled={isConnecting}
                className="w-full bg-gradient-to-r from-purple-600 to-blue-600 hover:from-purple-700 hover:to-blue-700"
              >
                {isConnecting ? (
                  <>
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                    Conectando...
                  </>
                ) : (
                  <>
                    <Wifi className="h-4 w-4 mr-2" />
                    Conectar
                  </>
                )}
              </Button>
            </CardContent>
          </Card>
        )}

        {/* Main Content */}
        {authData && (
          <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 mt-4">
            {/* Info */}
            <div className="lg:col-span-4">
              <Card className="bg-slate-800 border-slate-700">
                <CardContent className="p-4">
                  <div className="flex items-center justify-between">
                    <div className="text-sm text-slate-400">
                      <span className="text-white font-medium">{categories.length}</span> categorias
                    </div>
                    {isAndroidDevice && (
                      <Badge className="bg-green-500/20 text-green-400 border-green-500/50">
                        <Smartphone className="h-3 w-3 mr-1" />
                        Toque em FNG para FongMi / VLC para VLC
                      </Badge>
                    )}
                  </div>
                </CardContent>
              </Card>
            </div>

            {/* Categories */}
            <div className="lg:col-span-1">
              <Card className="bg-slate-800 border-slate-700">
                <CardHeader className="py-3">
                  <CardTitle className="text-sm">Categorias</CardTitle>
                </CardHeader>
                <CardContent className="p-0">
                  <ScrollArea className="h-[60vh]">
                    <div className="p-2 space-y-1">
                      {categories.map((cat) => (
                        <Button
                          key={cat.category_id}
                          variant={selectedCategory === cat.category_id ? 'default' : 'ghost'}
                          size="sm"
                          className="w-full justify-start truncate"
                          onClick={() => loadStreams(cat.category_id)}
                        >
                          {cat.category_name}
                        </Button>
                      ))}
                    </div>
                  </ScrollArea>
                </CardContent>
              </Card>
            </div>

            {/* Channels */}
            <div className="lg:col-span-3">
              {selectedCategory ? (
                <Card className="bg-slate-800 border-slate-700">
                  <CardHeader className="py-3">
                    <div className="flex items-center justify-between">
                      <CardTitle className="text-sm">Canais</CardTitle>
                      <div className="flex items-center gap-2">
                        <Badge variant="secondary">{filteredStreams.length}</Badge>
                        <div className="relative">
                          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
                          <Input
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            placeholder="Buscar..."
                            className="pl-9 w-48 h-8 bg-slate-700 border-slate-600"
                          />
                        </div>
                      </div>
                    </div>
                  </CardHeader>
                  <CardContent className="p-0">
                    <ScrollArea className="h-[55vh]">
                      {isLoading ? (
                        <div className="flex items-center justify-center py-12">
                          <Loader2 className="h-8 w-8 animate-spin" />
                        </div>
                      ) : (
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-2 p-2">
                          {filteredStreams.map((stream) => (
                            <div
                              key={stream.streamId}
                              className="flex items-center gap-2 p-2 rounded-lg bg-slate-700/50 hover:bg-slate-700 transition-colors"
                            >
                              {stream.icon && (
                                <img 
                                  src={stream.icon} 
                                  alt="" 
                                  className="h-10 w-10 rounded object-contain bg-slate-600 p-1 flex-shrink-0"
                                  onError={(e) => { e.currentTarget.style.display = 'none' }}
                                />
                              )}
                              <div className="flex-1 min-w-0">
                                <p className="text-sm font-medium truncate">{stream.name}</p>
                              </div>
                              
                              {/* Action Buttons */}
                              <div className="flex items-center gap-1 flex-shrink-0">
                                {/* FongMi Button - Android */}
                                {isAndroidDevice && (
                                  <Button
                                    size="sm"
                                    variant="default"
                                    className="h-8 px-2 bg-gradient-to-r from-purple-600 to-blue-600"
                                    onClick={() => openInFongMi(stream)}
                                    title="Abrir no FongMi/TV"
                                  >
                                    <span className="text-xs font-bold">FNG</span>
                                  </Button>
                                )}
                                
                                {/* VLC Button - Android */}
                                {isAndroidDevice && (
                                  <Button
                                    size="sm"
                                    variant="outline"
                                    className="h-8 px-2 border-orange-500 text-orange-400 hover:bg-orange-500/20"
                                    onClick={() => openInVLC(stream)}
                                    title="Abrir no VLC"
                                  >
                                    <span className="text-xs font-bold">VLC</span>
                                  </Button>
                                )}
                                
                                {/* Desktop - Open in new tab */}
                                {!isAndroidDevice && (
                                  <Button
                                    size="sm"
                                    variant="default"
                                    className="h-8 px-2"
                                    onClick={() => openInNewTab(stream)}
                                    title="Abrir em nova aba"
                                  >
                                    <ExternalLink className="h-4 w-4" />
                                  </Button>
                                )}
                                
                                {/* Copy Button */}
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  className="h-8 px-2"
                                  onClick={() => copyUrl(stream)}
                                  title="Copiar URL"
                                >
                                  {copiedUrl === stream.streamId ? (
                                    <Check className="h-4 w-4 text-green-400" />
                                  ) : (
                                    <Copy className="h-4 w-4" />
                                  )}
                                </Button>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </ScrollArea>
                  </CardContent>
                </Card>
              ) : (
                <Card className="bg-slate-800 border-slate-700 h-full flex items-center justify-center">
                  <CardContent className="text-center py-12">
                    <Tv className="h-16 w-16 text-slate-600 mx-auto mb-4" />
                    <p className="text-slate-400">Selecione uma categoria</p>
                  </CardContent>
                </Card>
              )}
            </div>
          </div>
        )}
      </main>

      {/* Legend */}
      {authData && isAndroidDevice && selectedCategory && (
        <div className="fixed bottom-4 left-4 right-4 z-50">
          <Card className="bg-slate-800/95 backdrop-blur border-slate-700">
            <CardContent className="p-2 flex items-center justify-center gap-4 text-xs text-slate-400">
              <span className="font-bold text-white">FNG</span>
              <span>= FongMi/TV (melhor para TV)</span>
              <span className="text-slate-600">|</span>
              <span className="text-orange-400 font-bold">VLC</span>
              <span>= VLC Player</span>
              <span className="text-slate-600">|</span>
              <Copy className="h-4 w-4" />
              <span>= Copiar</span>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}
