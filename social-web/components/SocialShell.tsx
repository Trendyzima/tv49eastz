'use client';
import Link from 'next/link';
import {usePathname} from 'next/navigation';
import {useEffect,useState} from 'react';
import {supabase} from '../lib/supabase';

const items=[['/','⌂','Home'],['/explore','⌕','Explore'],['/notifications','♡','Notifications'],['/messages','✉','Messages'],['/bookmarks','🔖','Bookmarks'],['/communities','◉','Communities'],['/spaces','◉','Spaces'],['/reels','▶','Reels'],['/profile','♙','Profile'],['/settings','⚙','Settings'],['/privacy','⌁','Privacy'],['/terms','§','Terms']];
export function SocialShell({children}:{children:React.ReactNode}){
 const path=usePathname(); const [user,setUser]=useState<any>(null); const [dark,setDark]=useState(false);
 useEffect(()=>{supabase.auth.getUser().then(({data})=>setUser(data.user));const t=localStorage.getItem('testagram-theme');setDark(t==='dark');const {data}=supabase.auth.onAuthStateChange((_,s)=>setUser(s?.user??null));return()=>data.subscription.unsubscribe()},[]);
 useEffect(()=>{document.documentElement.dataset.theme=dark?'dark':'light';localStorage.setItem('testagram-theme',dark?'dark':'light')},[dark]);
 async function logout(){await supabase.auth.signOut()}
 return <div className="app-shell"><aside className="sidebar"><Link className="brand" href="/"><span>49</span><strong>Testagram</strong></Link><nav>{items.map(([href,icon,label])=><Link className={path===href?'active':''} key={href} href={href}><span>{icon}</span>{label}</Link>)}</nav><div className="sidebar-bottom"><Link className="btn primary full" href="/media">＋ Create</Link><button className="nav-btn" onClick={()=>setDark(v=>!v)}><span>{dark?'☀':'☾'}</span>{dark?'Light mode':'Dark mode'}</button>{user?<button className="nav-btn" onClick={logout}><span>↪</span>Sign out</button>:<Link className="btn ghost full" href="/settings#account">Sign in / Join</Link>}</div></aside><header className="mobile-top"><Link className="brand" href="/"><span>49</span><strong>Testagram</strong></Link><Link href="/settings">⚙</Link></header><main className="content">{children}</main></div>
}
