import type {Metadata} from 'next';
import './styles.css';
import {SocialShell} from '../components/SocialShell';
export const metadata:Metadata={title:'Testagram — TV 49 East Social',description:'Modern social platform for TV 49 East.'};
export default function RootLayout({children}:{children:React.ReactNode}){return <html lang="en"><body><SocialShell>{children}</SocialShell></body></html>}
