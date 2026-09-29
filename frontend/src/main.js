import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import HomeView from './views/HomeView.vue'
import PlayerView from './views/PlayerView.vue'
import FavoritesView from './views/FavoritesView.vue'
import HistoryView from './views/HistoryView.vue'
import SettingsView from './views/SettingsView.vue'
import './style.css'

const routes = [
  { path: '/', component: HomeView },
  { path: '/player', component: PlayerView },
  { path: '/history', component: HistoryView },
  { path: '/favorites', component: FavoritesView },
  { path: '/settings', component: SettingsView },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

createApp(App).use(router).mount('#app')
