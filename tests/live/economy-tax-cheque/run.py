#!/usr/bin/env python3
"""Live gate for shorthand amounts, /cheque, GPExpansion-style tax + Tax account, /tax, and priced signs."""
from pathlib import Path
import hashlib as _hashlib
import sqlite3
import sys as _sys
import uuid as _uuid

BASE = Path(__file__).resolve().parents[1] / 'staff/run.py'
OWN = Path(__file__).resolve()
_source = BASE.read_text()
__file__ = str(BASE)
exec(compile(_source[:_source.rindex('\nmain()')], str(BASE), 'exec'))
__file__ = str(OWN)
SOURCE_HASHES[str(OWN.relative_to(REPO))] = _hashlib.sha256(OWN.read_bytes()).hexdigest()


def offline_uuid(name):
    h = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    h[6] = h[6] & 0x0f | 0x30
    h[8] = h[8] & 0x3f | 0x80
    return str(_uuid.UUID(bytes=bytes(h)))


TAX_UUID = offline_uuid('Tax')


def db_balance(lane, name_or_uuid):
    uid = name_or_uuid if '-' in name_or_uuid else offline_uuid(name_or_uuid)
    with sqlite3.connect(lane.path / 'plugins/Allium/allium.db') as db:
        row = db.execute('SELECT balance_minor, reserved_minor FROM allium_economy_accounts WHERE account_uuid=?', (uid,)).fetchone()
        return None if row is None else (row[0], row[1])


def db_query(lane, sql, params=()):
    with sqlite3.connect(lane.path / 'plugins/Allium/allium.db') as db:
        return db.execute(sql, params).fetchall()


def console_wait(lane, cmd, needle):
    start = len(lane.logs)
    lane.console(cmd)
    return wait(lambda: next((v for v in lane.logs[start:] if needle in v), None), f'console {cmd!r} -> {needle!r}')


def signedit(bot, x, y, z, lines):
    start = len(bot.lines)
    bot.write('signedit ' + json.dumps(dict(location=dict(x=x, y=y, z=z), front=True, lines=lines)))
    wait(lambda: any('SIGN-SENT edit' in s for s in bot.lines[start:]), 'sign edit sent')
    window(.4)


def shorthand_and_tax_pay(lane):
    player, staff = lane.bots['PlayerBot'], lane.bots['StaffBot']
    lane.perm('PlayerBot', 'allium.balance.others')
    lane.perm('StaffBot', 'allium.baltop')
    lane.perm('StaffBot', 'allium.eco')

    # Tax system account opened at $0.00 automatically.
    wait(lambda: db_balance(lane, TAX_UUID) == (0, 0), 'Tax account opened at 0.00')
    bal_tax = player.command('bal Tax', 'Balance:')
    require('$0' in bal_tax['plain'], lane.name + ': /bal Tax reports $0 at startup', bal_tax['plain'])

    # Shorthand amounts in /money and /eco.
    lane.console('money give PlayerBot 100k')
    lane.console('eco give StaffBot 1M')
    wait(lambda: db_balance(lane, 'PlayerBot') == (10_010_000, 0) and db_balance(lane, 'StaffBot') == (100_010_000, 0),
         'shorthand 100k and 1M applied')
    require(db_balance(lane, 'PlayerBot') == (10_010_000, 0), lane.name + ': /money give PlayerBot 100k exact minor units')
    require(db_balance(lane, 'StaffBot') == (100_010_000, 0), lane.name + ': /eco give StaffBot 1M exact minor units')

    # Taxed /pay (default 5%): 100k -> 95k to StaffBot, 5k to Tax.
    smark = len(staff.lines)
    sent = player.command('pay StaffBot 100k', 'tax to Tax')
    staff.chat(smark, 'Received $95,000 from PlayerBot')
    wait(lambda: db_balance(lane, TAX_UUID) == (500_000, 0), 'Tax received 5k')
    require(db_balance(lane, 'PlayerBot') == (10_000, 0)
            and db_balance(lane, 'StaffBot') == (109_510_000, 0)
            and db_balance(lane, TAX_UUID) == (500_000, 0),
            lane.name + ': /pay 100k splits 95k recipient + 5k Tax atomically', sent['plain'])
    sums = db_query(lane, """
        SELECT SUM(e.book_delta), COUNT(*)
        FROM allium_economy_entries e
        JOIN allium_economy_operations o ON o.operation_id = e.operation_id
        WHERE o.kind = 'PAY'
        GROUP BY e.operation_id
        ORDER BY o.created_at DESC LIMIT 1
    """)
    require(sums == [(0, 3)], lane.name + ': taxed PAY writes 3 balanced ledger entries summing to 0', sums)

    # Exempt player skips tax completely.
    lane.perm('PlayerBot', 'allium.tax.exempt', True)
    smark = len(staff.lines)
    player.command('pay StaffBot 100', 'Paid $100 to StaffBot')
    staff.chat(smark, 'Received $100 from PlayerBot')
    wait(lambda: db_balance(lane, 'PlayerBot') == (0, 0), 'exempt pay settled')
    require(db_balance(lane, 'StaffBot') == (109_520_000, 0) and db_balance(lane, TAX_UUID) == (500_000, 0),
            lane.name + ': allium.tax.exempt skips /pay tax')
    lane.perm('PlayerBot', 'allium.tax.exempt', False)

    # /baltop resolves player names and Tax on separate lines (never raw UUIDs).
    tmark = len(staff.lines)
    staff.command('baltop', 'Balancetop [1/1]')
    staff.chat(tmark, 'Server Total:')
    staff.chat(tmark, '#1 StaffBot')
    staff.chat(tmark, '#2 Tax')
    recent = '\n'.join(c.get('plain', '') for c in staff.chats(tmark))
    require(TAX_UUID not in recent, lane.name + ': /baltop prints names and Tax on separate lines instead of raw UUIDs', recent)


def cheques_player_and_admin(lane):
    player, staff = lane.bots['PlayerBot'], lane.bots['StaffBot']
    lane.perm('StaffBot', 'allium.cheque.others')
    lane.perm('PlayerBot', 'allium.cheque.others', False)
    lane.fixture('clear PlayerBot')
    ref = lane.fixture('blanksign StaffBot 4 4')
    ground_y = ref['y'] - 1
    lane.fixture(f"tp PlayerBot 4.5 {ref['y']} 4.5 0 0")
    lane.console('eco set PlayerBot 1k')
    wait(lambda: db_balance(lane, 'PlayerBot') == (100_000, 0), 'PlayerBot set to 1k')

    # 1. Player-funded untaxed cheque (apply-to.cheques: false by default).
    tax_before = db_balance(lane, TAX_UUID)[0]
    player.command('cheque 200', 'Created a cheque for $200')
    wait(lambda: db_balance(lane, 'PlayerBot') == (80_000, 0), 'player cheque debited 200')
    require(db_balance(lane, TAX_UUID)[0] == tax_before, lane.name + ': default apply-to.cheques=false charges no cheque tax')
    inv = lane.fixture('inv PlayerBot')['slots']
    paper_slot = next(int(k) for k, v in inv.items() if v['type'] == 'PAPER')
    lane.fixture(f'held PlayerBot {paper_slot}')
    window(.2)
    mark = player.signuse(4, ground_y, 4)
    player.chat(mark, 'Redeemed a cheque for $200')
    wait(lambda: db_balance(lane, 'PlayerBot') == (100_000, 0), 'player cheque redeemed')
    require(db_query(lane, "SELECT COUNT(*) FROM allium_economy_cheques WHERE state='REDEEMED'")[0][0] == 1,
            lane.name + ': redeemed cheque marked REDEEMED in DB')

    # 2. Taxed player-funded cheque when apply-to.cheques: true.
    cfg_path = lane.path / 'plugins/Allium/config.yml'
    cfg = yaml.safe_load(cfg_path.read_text())
    cfg['economy']['tax']['apply-to']['cheques'] = True
    cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
    console_wait(lane, 'allium reload', 'Configuration reloaded.')
    lane.fixture('clear PlayerBot')
    player.command('cheque 200', 'Created a cheque for $190 ($10 tax to Tax)')
    wait(lambda: db_balance(lane, 'PlayerBot') == (80_000, 0) and db_balance(lane, TAX_UUID)[0] == tax_before + 1_000,
         'taxed cheque debited 200 and credited Tax 10')
    inv = lane.fixture('inv PlayerBot')['slots']
    paper_slot = next(int(k) for k, v in inv.items() if v['type'] == 'PAPER')
    lane.fixture(f'held PlayerBot {paper_slot}')
    window(.2)
    mark = player.signuse(4, ground_y, 4)
    player.chat(mark, 'Redeemed a cheque for $190')
    wait(lambda: db_balance(lane, 'PlayerBot') == (99_000, 0), 'taxed cheque face value 190 redeemed')
    cfg['economy']['tax']['apply-to']['cheques'] = False
    cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
    console_wait(lane, 'allium reload', 'Configuration reloaded.')

    # 3. Admin cheque permission fence, ranged console cheque (500-1000), and staff admin cheque (no staff debit).
    player.command('cheque 500 StaffBot', 'permission')
    lane.fixture('clear PlayerBot')
    pmark = len(player.lines)
    console_wait(lane, 'cheque 500-1000 PlayerBot', 'Gave an admin cheque for')
    rec = player.chat(pmark, 'You received a cheque for')
    require('from Console' in rec['plain'], lane.name + ': console ranged cheque signed by Console', rec['plain'])
    rows = db_query(lane, "SELECT amount_minor, admin_issued, issuer_name FROM allium_economy_cheques WHERE state='ISSUED' ORDER BY created_at DESC LIMIT 1")
    require(len(rows) == 1 and 50_000 <= rows[0][0] <= 100_000 and rows[0][0] % 100 == 0 and rows[0][1] == 1 and rows[0][2] == 'Console',
            lane.name + ': ranged 500-1000 admin cheque persisted within whole-dollar range', rows)
    ranged_minor = rows[0][0]
    inv = lane.fixture('inv PlayerBot')['slots']
    paper_slot = next(int(k) for k, v in inv.items() if v['type'] == 'PAPER')
    lane.fixture(f'held PlayerBot {paper_slot}')
    window(.2)
    mark = player.signuse(4, ground_y, 4)
    player.chat(mark, 'Redeemed a cheque for')
    wait(lambda: db_balance(lane, 'PlayerBot') == (99_000 + ranged_minor, 0), 'ranged admin cheque redeemed')

    staff_before = db_balance(lane, 'StaffBot')[0]
    player_before = db_balance(lane, 'PlayerBot')[0]
    lane.fixture('clear PlayerBot')
    pmark = len(player.lines)
    staff.command('cheque 1k PlayerBot', 'Gave an admin cheque for $1,000 to PlayerBot')
    rec2 = player.chat(pmark, 'You received a cheque for $1,000 from StaffBot')
    require(db_balance(lane, 'StaffBot')[0] == staff_before, lane.name + ': staff admin cheque does not withdraw from staff')
    inv = lane.fixture('inv PlayerBot')['slots']
    paper_slot = next(int(k) for k, v in inv.items() if v['type'] == 'PAPER')
    lane.fixture(f'held PlayerBot {paper_slot}')
    window(.2)
    mark = player.signuse(4, ground_y, 4)
    player.chat(mark, 'Redeemed a cheque for $1,000 (signed by StaffBot)')
    wait(lambda: db_balance(lane, 'PlayerBot')[0] == player_before + 100_000, 'staff admin cheque redeemed')


def tax_command(lane):
    player, staff = lane.bots['PlayerBot'], lane.bots['StaffBot']
    for node in ['allium.tax.impose', 'allium.tax.history', 'allium.tax.refund']:
        lane.perm('StaffBot', node)
    lane.console('eco set PlayerBot 1k')
    wait(lambda: db_balance(lane, 'PlayerBot') == (100_000, 0), 'PlayerBot reset to 1k')

    info = player.command('tax', 'Tax: 5%')
    require('Account Tax:' in info['plain'], lane.name + ': /tax shows rate and Tax account balance', info['plain'])

    tax_before = db_balance(lane, TAX_UUID)[0]
    pmark = len(player.lines)
    staff.command('tax impose PlayerBot 10', 'Imposed 10% tax ($100) on PlayerBot')
    player.chat(pmark, 'A 10% tax ($100) was imposed')
    wait(lambda: db_balance(lane, 'PlayerBot') == (90_000, 0) and db_balance(lane, TAX_UUID)[0] == tax_before + 10_000,
         'tax impose moved 100 to Tax')
    mail_rows = lane.fixture('mailbox PlayerBot')['rows']
    require(any('10%' in r['body'] and '$100' in r['body'] for r in mail_rows),
            lane.name + ': /tax impose records a mail receipt for the target', mail_rows)

    hist = staff.command('tax history PlayerBot', 'Tax history for PlayerBot')
    require(db_query(lane, 'SELECT COUNT(*) FROM allium_economy_tax_history')[0][0] == 1,
            lane.name + ': tax history persisted in SQLite', hist['plain'])

    # Refund requires confirmation; unconfirmed prompt moves nothing.
    staff.command('tax refund 1 50', 'Confirm refund of tax #1')
    window(.3)
    require(db_balance(lane, 'PlayerBot') == (90_000, 0), lane.name + ': unconfirmed /tax refund does not move money')

    pmark = len(player.lines)
    staff.command('tax refund 1 50 confirm', 'Refunded $50 for tax #1 to PlayerBot')
    player.chat(pmark, 'You received a tax refund of $50')
    wait(lambda: db_balance(lane, 'PlayerBot') == (95_000, 0) and db_balance(lane, TAX_UUID)[0] == tax_before + 5_000,
         'confirmed 50% refund settled')
    staff.command('tax refund 1 100 confirm', 'already been refunded')
    require(db_balance(lane, 'PlayerBot') == (95_000, 0), lane.name + ': double refund refused without money effect')

    # Bulk /tax impose * skips exempt players.
    lane.perm('StaffBot', 'allium.tax.exempt', True)
    staff_before = db_balance(lane, 'StaffBot')[0]
    staff.command('tax impose * 10', 'Imposed 10% tax on 1 player(s)')
    wait(lambda: db_balance(lane, 'PlayerBot') == (85_500, 0), 'bulk impose taxed PlayerBot')
    require(db_balance(lane, 'StaffBot')[0] == staff_before, lane.name + ': /tax impose * skips exempt StaffBot')
    lane.perm('StaffBot', 'allium.tax.exempt', False)


def money_signs(lane):
    player, staff = lane.bots['PlayerBot'], lane.bots['StaffBot']
    # Enable signs + add a $500 vip kit, keep unsafe-sign-prices: false.
    cfg_path = lane.path / 'plugins/Allium/config.yml'
    cfg = yaml.safe_load(cfg_path.read_text())
    cfg['enabledSigns'] = ['kit', 'warp', 'heal', 'repair']
    cfg['unsafe-sign-prices'] = False
    cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
    kits_dir = lane.path / 'plugins/Allium/kits'
    kits_dir.mkdir(parents=True, exist_ok=True)
    (kits_dir / 'vip.yml').write_text("enabled: true\ncooldown-seconds: 0\none-time: false\ncost: '500.00'\nitems:\n  - 'minecraft:diamond 1'\n")
    console_wait(lane, 'allium reload', 'Configuration reloaded.')

    for node in ['allium.signs.create.kit', 'allium.signs.create.warp', 'allium.signs.create.warp.public',
                 'allium.signs.create.heal', 'allium.setwarp']:
        lane.perm('StaffBot', node)
    for node in ['allium.signs.use.kit', 'allium.signs.use.warp', 'allium.signs.use.heal',
                 'allium.kit', 'allium.kits.starter', 'allium.kits.vip', 'allium.warp', 'allium.heal']:
        lane.perm('PlayerBot', node)

    lane.fixture('tp StaffBot 8.5 -60 8.5 0 0')
    lane.fixture('tp PlayerBot 8.5 -60 8.5 0 0')
    window(.3)

    # 1. Priced [Kit] sign for free starter kit ($400 overrides 0.00).
    s1 = lane.fixture('blanksign StaffBot 9 8')
    lane.fixture(f"tp StaffBot 8.5 {s1['y']} 8.5 0 0")
    lane.fixture(f"tp PlayerBot 8.5 {s1['y']} 8.5 0 0")
    window(.3)
    lane.fixture(f"opensign StaffBot {s1['x']} {s1['y']} {s1['z']}")
    signedit(staff, s1['x'], s1['y'], s1['z'], ['[Kit]', 'starter', '$400', ''])
    st1 = lane.fixture(f"signstate {s1['x']} {s1['y']} {s1['z']}")
    require(st1['frontPdc'] and '$400' in st1['lines'][2], lane.name + ': priced [Kit] starter $400 sign created', st1)

    lane.fixture('clear PlayerBot')
    lane.console('eco set PlayerBot 100')
    wait(lambda: db_balance(lane, 'PlayerBot') == (10_000, 0), 'PlayerBot set to 100')
    mark = player.signuse(s1['x'], s1['y'], s1['z'])
    player.chat(mark, 'could not be paid')
    require(db_balance(lane, 'PlayerBot') == (10_000, 0) and not lane.fixture('inv PlayerBot')['slots'],
            lane.name + ': priced [Kit] sign refuses insufficient funds with no items or debit')

    lane.console('eco set PlayerBot 500')
    wait(lambda: db_balance(lane, 'PlayerBot') == (50_000, 0), 'PlayerBot set to 500')
    mark = player.signuse(s1['x'], s1['y'], s1['z'])
    player.chat(mark, 'Received kit starter')
    wait(lambda: db_balance(lane, 'PlayerBot') == (10_000, 0), 'priced kit sign charged 400')
    require(len(lane.fixture('inv PlayerBot')['slots']) > 0, lane.name + ': priced [Kit] sign delivered starter items and charged $400')

    # 2. unsafe-sign-prices: false blocks discounted $250 sign for $500 vip kit; true allows $100 sign & overrides cost.
    s2 = lane.fixture('blanksign StaffBot 10 8')
    smark = len(staff.lines)
    signedit(staff, s2['x'], s2['y'], s2['z'], ['[Kit]', 'vip', '$250', ''])
    staff.chat(smark, 'below the configured cost')
    st2 = lane.fixture(f"signstate {s2['x']} {s2['y']} {s2['z']}")
    require(st2['frontPdc'] is None, lane.name + ': unsafe-sign-prices=false refuses discounted kit sign without PDC', st2)

    cfg['unsafe-sign-prices'] = True
    cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
    console_wait(lane, 'allium reload', 'Configuration reloaded.')
    lane.fixture(f"opensign StaffBot {s2['x']} {s2['y']} {s2['z']}")
    signedit(staff, s2['x'], s2['y'], s2['z'], ['[Kit]', 'vip', '$100', ''])
    st2b = lane.fixture(f"signstate {s2['x']} {s2['y']} {s2['z']}")
    require(st2b['frontPdc'] is not None and '$100' in st2b['lines'][2],
            lane.name + ': unsafe-sign-prices=true allows discounted $100 sign for $500 vip kit', st2b)

    lane.fixture('clear PlayerBot')
    mark = player.signuse(s2['x'], s2['y'], s2['z'])
    player.chat(mark, 'Received kit vip')
    wait(lambda: db_balance(lane, 'PlayerBot') == (0, 0), 'discounted vip kit sign charged 100')
    inv = lane.fixture('inv PlayerBot')['slots']
    require(any(v['type'] == 'DIAMOND' for v in inv.values()),
            lane.name + ': discounted [Kit] sign bypassed $500 command price and delivered vip for $100', inv)

    # 3. Priced [Heal] ($25) and priced public [Warp] ($50) signs.
    s3 = lane.fixture('blanksign StaffBot 11 8')
    signedit(staff, s3['x'], s3['y'], s3['z'], ['[Heal]', '', '$25', ''])
    lane.fixture(f"tp StaffBot 24.5 {s1['y']} 24.5 0 0")
    window(.2)
    staff.command('setwarp market', 'Warp')
    lane.fixture(f"tp StaffBot 8.5 {s1['y']} 8.5 0 0")
    window(.2)
    s4 = lane.fixture('blanksign StaffBot 12 8')
    signedit(staff, s4['x'], s4['y'], s4['z'], ['[Warp]', 'market', 'public', '$50'])

    lane.fixture('health PlayerBot 10')
    mark = player.signuse(s3['x'], s3['y'], s3['z'])
    player.chat(mark, 'You need $25')
    require(lane.fixture('health PlayerBot')['health'] == 10.0, lane.name + ': priced [Heal] refuses when broke without healing')

    lane.console('eco set PlayerBot 100')
    wait(lambda: db_balance(lane, 'PlayerBot') == (10_000, 0), 'PlayerBot funded 100 for signs')
    mark = player.signuse(s3['x'], s3['y'], s3['z'])
    player.chat(mark, 'Paid $25 for [Heal]')
    wait(lambda: lane.fixture('health PlayerBot')['health'] == 20.0 and db_balance(lane, 'PlayerBot') == (7_500, 0),
         'priced heal sign healed and charged 25')

    mark = player.signuse(s4['x'], s4['y'], s4['z'])
    player.chat(mark, 'Paid $50 for [Warp]')
    wait(lambda: abs(lane.fixture('where PlayerBot')['x'] - 24.5) < 1.0 and db_balance(lane, 'PlayerBot') == (2_500, 0),
         'priced public warp sign warped and charged 50')


def post_db_checks(lane):
    with sqlite3.connect(lane.path / 'plugins/Allium/allium.db') as db:
        held = db.execute("SELECT COUNT(*) FROM allium_economy_reservations WHERE state='HELD'").fetchone()[0]
        res_sum = db.execute("SELECT COALESCE(SUM(reserved_minor),0) FROM allium_economy_accounts").fetchone()[0]
        pending_chq = db.execute("SELECT COUNT(*) FROM allium_economy_cheques WHERE state='PENDING'").fetchone()[0]
        fk = db.execute("PRAGMA foreign_key_check").fetchall()
    require(held == 0 and res_sum == 0 and pending_chq == 0 and not fk,
            lane.name + ': post-shutdown SQLite invariants clean (0 held, 0 reserved, 0 pending cheques, 0 FK violations)',
            dict(held=held, res_sum=res_sum, pending_chq=pending_chq, fk=fk))


def econ_main():
    build_helper()
    lane = Lane(A.suite, PORT)
    try:
        lane.boot()
        setup(lane)
        case('shorthand-and-tax-pay', lambda: shorthand_and_tax_pay(lane))
        case('cheques-player-and-admin', lambda: cheques_player_and_admin(lane))
        case('tax-command', lambda: tax_command(lane))
        case('money-signs-and-unsafe-prices', lambda: money_signs(lane))
    finally:
        try:
            lane.stop()
        except Exception as error:
            require(False, 'shutdown: ' + str(error))
        clean_logs(lane)
    post_db_checks(lane)
    manifest = dict(suite=A.suite, run=A.run_name, alliumSha256=A.sha256, sources=SOURCE_HASHES, passed=not failures,
                    checks=len(checks), failures=failures, cases=cases, seconds=round(time.time() - started, 1), results=checks)
    (RUN / 'manifest.json').write_text(json.dumps(manifest, indent=2, default=str))
    emit(f'RESULT {"PASS" if not failures else "FAIL"} checks={len(checks)} failures={len(failures)} manifest={RUN / "manifest.json"}')
    _sys.exit(0 if not failures else 1)


econ_main()
