/* jacbridge - pont stdin/stdout <-> port serie MCU Jancar (aarch64, sans libc), lance par l'app JacMCU via su.
 *   jacbridge w  <tty>        : chaque ligne hexa lue sur stdin ("ee fa 02 08 01 f3") -> UN seul write() sur le port.
 *                               Le port n'est pas reconfigure (ivi-services l'a deja regle a 115200 8N1 brut).
 *   jacbridge rw <tty> [cfg]  : idem + recopie de tout ce qui est lu sur le port vers stdout ("R ee fa ...").
 *                               ATTENTION : vole les octets a ivi-services s'il tourne. "cfg" = regle 115200 8N1 brut.
 * Sortie : "OK <n>" apres chaque ecriture, "E <errno> <msg>" en cas d'erreur, "R <hex>" pour les octets recus.
 * Le processus se termine quand stdin est ferme (fin de l'app). */
typedef long s64; typedef unsigned int u32;
static s64 sys(s64 n,s64 a,s64 b,s64 c,s64 d){register s64 x8 asm("x8")=n,x0 asm("x0")=a,x1 asm("x1")=b,x2 asm("x2")=c,x3 asm("x3")=d;
 asm volatile("svc 0":"+r"(x0):"r"(x8),"r"(x1),"r"(x2),"r"(x3):"memory");return x0;}
#define SYS_ioctl 29
#define SYS_openat 56
#define SYS_read 63
#define SYS_write 64
#define SYS_ppoll 73
#define AT_FDCWD -100
#define TCGETS 0x5401
#define TCSETS 0x5402
struct ktermios{u32 c_iflag,c_oflag,c_cflag,c_lflag;unsigned char c_line,c_cc[19];};
struct pollfd{int fd;short events,revents;};
static const char hx[]="0123456789abcdef";
static int slen(const char*s){int n=0;while(s[n])n++;return n;}
static void out(const char*s,int n){while(n>0){s64 w=sys(SYS_write,1,(s64)s,n,0);if(w<=0)return;s+=w;n-=w;}}
static void outs(const char*s){out(s,slen(s));}
static void outn(s64 v){char b[24];int i=23;b[i]=0;int neg=v<0;if(neg)v=-v;do{b[--i]='0'+v%10;v/=10;}while(v);if(neg)b[--i]='-';outs(b+i);}
static int streq(const char*a,const char*b){while(*a&&*a==*b){a++;b++;}return *a==*b;}
static int hv(char c){return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;}

static int tty=-1;
static void send_line(const char*l,int n){
 unsigned char f[300];int k=0,h=-1;
 for(int i=0;i<n;i++){int v=hv(l[i]);if(v<0){if(h>=0){f[k++]=(unsigned char)h;h=-1;}continue;}
  if(h<0)h=v;else{if(k>=300){outs("E 0 trop long\n");return;}f[k++]=(unsigned char)(h*16+v);h=-1;}}
 if(h>=0&&k<300)f[k++]=(unsigned char)h;
 if(!k)return;
 s64 w=sys(SYS_write,tty,(s64)f,k,0);
 if(w==k){outs("OK ");outn(w);outs("\n");}else{outs("E ");outn(w<0?-w:0);outs(" write\n");}
}
static void dump(const unsigned char*b,int n){char o[3*512+4];int k=0;o[k++]='R';
 for(int i=0;i<n;i++){o[k++]=' ';o[k++]=hx[b[i]>>4];o[k++]=hx[b[i]&15];}o[k++]='\n';out(o,k);}

int start_c(s64*sp){
 int argc=(int)sp[0];char**argv=(char**)(sp+1);
 if(argc<3||!(streq(argv[1],"w")||streq(argv[1],"rw"))){outs("usage: jacbridge w|rw <tty> [cfg]\n");return 2;}
 int rd=streq(argv[1],"rw");
 tty=(int)sys(SYS_openat,AT_FDCWD,(s64)argv[2],(rd?2:1)|0400/*NOCTTY*/|(rd?04000:0)/*NONBLOCK*/,0);
 if(tty<0){outs("E ");outn(-tty);outs(" open\n");return 1;}
 if(rd&&argc>3&&streq(argv[3],"cfg")){struct ktermios t;if(sys(SYS_ioctl,tty,TCGETS,(s64)&t,0)==0){
  t.c_iflag=0;t.c_oflag=0;t.c_lflag=0;
  t.c_cflag=(t.c_cflag&~(0010017u|0000060u|0000400u|0000100u|020000000000u))|0010002u|0000060u|0000200u|0004000u;
  t.c_cc[6]=0;t.c_cc[5]=0;sys(SYS_ioctl,tty,TCSETS,(s64)&t,0);}}
 outs("READY ");outs(argv[1]);outs(" ");outs(argv[2]);outs("\n");
 char line[1024];int ll=0;unsigned char rb[512];
 for(;;){
  struct pollfd p[2]={{0,1,0},{tty,1,0}};
  s64 r=sys(SYS_ppoll,(s64)p,rd?2:1,0,0);
  if(r<0)continue;
  if(rd&&(p[1].revents&1)){s64 n=sys(SYS_read,tty,(s64)rb,sizeof rb,0);if(n>0)dump(rb,(int)n);}
  if(p[0].revents&(1|8|16)){
   char b[256];s64 n=sys(SYS_read,0,(s64)b,sizeof b,0);
   if(n<=0)return 0;                                   /* stdin ferme : fin */
   for(int i=0;i<n;i++){if(b[i]=='\n'){send_line(line,ll);ll=0;}else if(ll<(int)sizeof line)line[ll++]=b[i];}
  }
 }
}
__attribute__((naked)) void _start(void){asm volatile("mov x0, sp\n bl start_c\n mov x8,#93\n svc 0");}
