import { Navigate, Route, Routes } from 'react-router-dom'
import { ProtectedRoute, PublicOnlyRoute } from './auth/ProtectedRoute'
import { AppShell } from './components/AppShell'
import { AddBookPage } from './pages/AddBookPage'
import { BookDetailPage } from './pages/BookDetailPage'
import { ChatPage } from './pages/ChatPage'
import { ComingSoonPage, NotFoundPage } from './pages/ComingSoonPage'
import { HomePage } from './pages/HomePage'
import { LibraryPage } from './pages/LibraryPage'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { SettingsPage } from './pages/SettingsPage'

export default function App() {
  return (
    <Routes>
      <Route element={<PublicOnlyRoute />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
      </Route>

      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          <Route index element={<HomePage />} />
          <Route path="library" element={<LibraryPage />} />
          <Route path="library/add" element={<AddBookPage />} />
          <Route path="library/:id" element={<BookDetailPage />} />
          {/* one route for /chat and /chat/:id so switching conversations keeps the page (and any live stream) mounted */}
          <Route path="chat/:conversationId?" element={<ChatPage />} />
          <Route
            path="memory"
            element={
              <ComingSoonPage
                title="Memory"
                blurb="Soon Book Buddy will remember what you loved, and what you didn't."
              />
            }
          />
          <Route path="settings" element={<SettingsPage />} />
          <Route path="home" element={<Navigate to="/" replace />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
