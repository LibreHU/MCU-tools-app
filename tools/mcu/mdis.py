import sys
from ana import *
def lit(i):
    if i.mnemonic=='ldr' and len(i.operands)==2 and i.operands[1].type==ARM_OP_MEM and i.operands[1].mem.base==ARM_REG_PC:
        v=u32(((i.address+4)&~3)+i.operands[1].mem.disp); p=per(v) if v is not None else None
        return '  ; =0x%x %s'%(v,p or '')
    return ''
def dis(a,n=200):
    f=a
    for i in code:
        if i.address<a: continue
        if i.address in starts and i.address!=f and n<0: break
        print('%x: %-6s %s%s'%(i.address,i.mnemonic,i.op_str,lit(i)))
        n-=1
        if n==0: break
if __name__=='__main__':
    dis(int(sys.argv[1],16), int(sys.argv[2]) if len(sys.argv)>2 else 120)
