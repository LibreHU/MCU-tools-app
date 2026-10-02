from ana import *
# simple per-function register tracking (linear, reset at function start and after unconditional branches)
REG={ARM_REG_R0:0,ARM_REG_R1:1,ARM_REG_R2:2,ARM_REG_R3:3,ARM_REG_R4:4,ARM_REG_R5:5,ARM_REG_R6:6,ARM_REG_R7:7}
acc=collections.defaultdict(lambda:collections.defaultdict(set))  # func -> periph -> {(op,off)}
ramuse=collections.defaultdict(set)
labels=set(starts)
callsites=[]
for i in code:
    if i.mnemonic.startswith('b') and i.operands and i.operands[0].type==ARM_OP_IMM: labels.add(i.operands[0].imm)
r={}
for i in code:
    f=func_of(i.address)
    if i.address in labels: r={}
    m=i.mnemonic; ops=i.operands
    try:
        if m in('movs','mov') and len(ops)==2 and ops[1].type==ARM_OP_IMM and ops[0].reg in REG: r[ops[0].reg]=ops[1].imm
        elif m in('movs','mov') and len(ops)==2 and ops[1].type==ARM_OP_REG and ops[0].reg in REG:
            if ops[1].reg in r: r[ops[0].reg]=r[ops[1].reg]
            else: r.pop(ops[0].reg,None)
        elif m=='lsls' and len(ops)==3 and ops[2].type==ARM_OP_IMM and ops[1].reg in r: r[ops[0].reg]=(r[ops[1].reg]<<ops[2].imm)&0xffffffff
        elif m=='lsls' and len(ops)==2 and ops[1].type==ARM_OP_IMM and ops[0].reg in r: r[ops[0].reg]=(r[ops[0].reg]<<ops[1].imm)&0xffffffff
        elif m in('adds','add') and len(ops)==2 and ops[1].type==ARM_OP_IMM and ops[0].reg in r: r[ops[0].reg]=(r[ops[0].reg]+ops[1].imm)&0xffffffff
        elif m in('adds','add') and len(ops)==3 and ops[2].type==ARM_OP_IMM and ops[1].reg in r: r[ops[0].reg]=(r[ops[1].reg]+ops[2].imm)&0xffffffff
        elif m in('subs','sub') and len(ops)==2 and ops[1].type==ARM_OP_IMM and ops[0].reg in r: r[ops[0].reg]=(r[ops[0].reg]-ops[1].imm)&0xffffffff
        elif m=='ldr' and ops[1].type==ARM_OP_MEM and ops[1].mem.base==ARM_REG_PC:
            a=((i.address+4)&~3)+ops[1].mem.disp; v=u32(a)
            if v is not None: r[ops[0].reg]=v
        elif m[:3] in('ldr','str') and ops[1].type==ARM_OP_MEM and ops[1].mem.base in r and ops[1].mem.index==0:
            ea=(r[ops[1].mem.base]+ops[1].mem.disp)&0xffffffff
            p=per(ea)
            if p: acc[f][p.split('+')[0]].add((m[:3],int(p.split('+')[1],16)))
            elif 0x20000000<=ea<0x20002000: ramuse[f].add((m[:3],ea))
            if m.startswith('ldr') and ops[0].reg in REG: r.pop(ops[0].reg,None)
        elif ops and ops[0].type==ARM_OP_REG and ops[0].reg in r and m not in('cmp','tst','str','strb','strh','push','pop','b','bx','blx','cbz','cbnz'):
            r.pop(ops[0].reg,None)
        if m=='bl':
            args=tuple(r.get(k) for k in (ARM_REG_R0,ARM_REG_R1,ARM_REG_R2,ARM_REG_R3))
            callsites.append((i.address,f,ops[0].imm,args))
        if m in('bl','blx'):
            for k in (ARM_REG_R0,ARM_REG_R1,ARM_REG_R2,ARM_REG_R3): r.pop(k,None)
        if m in('b','bx','pop') : r={}
    except Exception as e: pass
if __name__=='__main__':
    for f in starts:
        if acc[f]:
            print(hex(f), {k:sorted(v) for k,v in acc[f].items()})
