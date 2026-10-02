import struct, collections
from capstone import *
from capstone.arm import *
B=0x08002400
import os, sys
raw=open(os.environ.get('JACMCU','jacmcu.bin'),'rb').read(); img=raw[16:]; END=B+len(img)
md=Cs(CS_ARCH_ARM, CS_MODE_THUMB); md.detail=True
def u32(a): o=a-B; return struct.unpack('<I',img[o:o+4])[0] if 0<=o<=len(img)-4 else None
vec=struct.unpack('<48I',img[:192])
PER={0x48000000:'GPIOA',0x48000400:'GPIOB',0x48000800:'GPIOC',0x48001400:'GPIOF',0x40013800:'USART1',0x40004400:'USART2',
0x40012400:'ADC',0x40012C00:'TIM1',0x40000400:'TIM3',0x40001000:'TIM6',0x40002000:'TIM14',0x40014000:'TIM15',0x40014400:'TIM16',0x40014800:'TIM17',
0x40003000:'IWDG',0x40002C00:'WWDG',0x40021000:'RCC',0x40022000:'FLASH',0x40010400:'EXTI',0x40010000:'SYSCFG',0x40005400:'I2C1',0x40005800:'I2C2',
0x40013000:'SPI1',0x40007000:'PWR',0x40002800:'RTC',0x40023000:'CRC',0x40020000:'DMA',0xE000E000:'SCS',0xE000E100:'NVIC',0xE000ED00:'SCB',0xE000E010:'SysTick',0x1FFFF7AC:'UID',0x1FFFF7B8:'VREFCAL'}
def per(v):
    for b,n in PER.items():
        if b<=v<b+0x400: return '%s+0x%x'%(n,v-b)
    return None
# linear sweep with function starts from BL targets + vectors
# recursive descent
ins={}
SWITCH={}
starts=set(v&~1 for v in vec[1:] if v and B<=v<END)
for o in range(0xC0,len(img)-3,4):
    v=struct.unpack('<I',img[o:o+4])[0]
    if (v&1) and B+0xC0<=v<END:
        t=v&~1; dl=list(md.disasm(img[t-B:t-B+2],t,count=1))
        if dl and dl[0].mnemonic=='push': starts.add(t)
work=list(starts); seen=set()
while work:
    a=work.pop()
    if a in seen or not (B<=a<END): continue
    while B<=a<END and a not in ins:
        seen.add(a)
        o=a-B
        dl=list(md.disasm(img[o:o+4],a,count=1))
        if not dl: break
        i=dl[0]; ins[a]=i; m=i.mnemonic; a+=i.size
        if m=='ldr' and len(i.operands)==2 and i.operands[1].type==ARM_OP_MEM and i.operands[1].mem.base==ARM_REG_PC:
            v=u32(((i.address+4)&~3)+i.operands[1].mem.disp)
            if v and (v&1) and B<=v<END: starts.add(v&~1); work.append(v&~1)
        if m=='bl' and i.operands[0].imm==0x80065ae:
            tb=i.address+4; N=img[tb-B]
            SWITCH[i.address]=[tb+2*img[tb+1+k-B] for k in range(N+1)]
            for t in SWITCH[i.address]: work.append(t)
            break
        if m=='bl': starts.add(i.operands[0].imm); work.append(i.operands[0].imm); continue
        if m.startswith('b') and m not in('bl','blx','bx','bic','bics') and i.operands and i.operands[0].type==ARM_OP_IMM:
            work.append(i.operands[0].imm)
            if m=='b': break
            continue
        if m in('cbz','cbnz'): work.append(i.operands[1].imm); continue
        if m=='bx' or (m=='pop' and 'pc' in i.op_str) or (m=='mov' and i.op_str.startswith('pc')): break
        if m=='ldr' and i.operands[0].reg==ARM_REG_PC: break
code=[ins[k] for k in sorted(ins)]
starts=sorted(s for s in starts if B<=s<END)
def func_of(a):
    import bisect
    k=bisect.bisect_right(starts,a)-1
    return starts[k] if k>=0 else None
info=collections.defaultdict(lambda:{'per':set(),'calls':set(),'consts':set(),'cmps':[]})
for i in code:
    f=func_of(i.address)
    if f is None: continue
    if i.mnemonic=='ldr' and len(i.operands)==2 and i.operands[1].type==ARM_OP_MEM and i.operands[1].mem.base==ARM_REG_PC:
        a=((i.address+4)&~3)+i.operands[1].mem.disp; v=u32(a)
        if v is not None:
            p=per(v)
            if p: info[f]['per'].add(p)
            elif 0x20000000<=v<0x20002000: info[f]['consts'].add('ram:%x'%v)
            elif B<=v<END: info[f]['consts'].add('rom:%x'%v)
            else: info[f]['consts'].add('#%x'%v)
    if i.mnemonic=='bl': info[f]['calls'].add(i.operands[0].imm)
    if i.mnemonic=='cmp' and i.operands[1].type==ARM_OP_IMM: info[f]['cmps'].append(i.operands[1].imm)
import sys
if __name__=='__main__':
    print('vectors:', {n:hex(vec[k]) for k,n in [(1,'reset'),(15,'systick')]+[(16+j,'irq%d'%j) for j in range(32)] if vec[k]})
    for f in starts:
        d=info[f]
        if d['per'] or len(sys.argv)>1:
            print(hex(f), sorted(d['per']), 'calls',len(d['calls']), 'cmps',sorted(set(d['cmps']))[:20], sorted(d['consts'])[:8])
