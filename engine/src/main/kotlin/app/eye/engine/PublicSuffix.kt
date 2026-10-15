package app.eye.engine

/**
 * Piccola Public Suffix List incorporata: ccTLD a due lettere (singolo livello), gTLD comuni,
 * suffissi multi-livello (co.uk, gov.it...) e hosting condiviso (github.io, pages.dev...).
 * Non sostituisce la PSL completa: è un'approssimazione prudente per mostrare l'eTLD+1.
 */
internal object PublicSuffix {
    private val gtlds = (
        "com net org info biz eu edu gov mil int name pro mobi app dev io co me tv cc ws xyz top click icu cfd " +
            "shop store online site website live vip buzz sbs cyou rest monster support work fit loan men date " +
            "review stream download racing win bid trade party science cricket faith accountant host space tech " +
            "link ltd cloud page cam bond help life world today digital services center email group news " +
            "finance money bank pay cash credit insurance legal tax solutions systems network company agency " +
            "fun one pw tk ml ga cf gq su"
        ).split(' ').toHashSet()

    private val multi = (
        "co.uk org.uk gov.uk ac.uk me.uk ltd.uk plc.uk net.uk sch.uk nhs.uk " +
            "gov.it edu.it " + IT_TERRITORIAL + " " +
            "com.br net.br org.br gov.br com.au net.au org.au gov.au edu.au co.nz org.nz govt.nz " +
            "co.jp ne.jp or.jp com.cn net.cn org.cn gov.cn com.tr net.tr org.tr gov.tr com.mx org.mx gob.mx " +
            "com.ar gob.ar com.co com.ve com.pe com.ua co.in net.in org.in gov.in co.za org.za gov.za " +
            "com.pl net.pl org.pl com.pt gov.pt com.es gob.es org.es com.fr gouv.fr asso.fr co.il com.sg " +
            "com.hk com.tw co.kr or.kr com.ph com.my com.vn co.id co.th com.eg com.ng com.sa com.pk com.bd " +
            "co.ke com.gh com.ru com.de co.at or.at gv.at ac.at co.hu com.ro com.gr gov.gr com.cy"
        ).split(' ').toHashSet()

    /** Suffissi territoriali italiani (province, sigle, regioni): i comuni vi stanno sotto (comune.bologna.it). */
    private const val IT_TERRITORIAL = "ag.it al.it an.it ao.it aq.it ar.it ap.it at.it av.it ba.it bt.it bl.it bn.it bg.it bi.it bo.it bz.it bs.it br.it ca.it cl.it cb.it ci.it ce.it ct.it cz.it ch.it co.it cs.it cr.it kr.it cn.it en.it fm.it fe.it fi.it fg.it fc.it fr.it ge.it go.it gr.it im.it is.it sp.it lt.it le.it lc.it li.it lo.it lu.it mc.it mn.it ms.it mt.it vs.it me.it mi.it mo.it mb.it na.it no.it nu.it og.it ot.it or.it pd.it pa.it pr.it pv.it pg.it pu.it pe.it pc.it pi.it pt.it pn.it pz.it po.it rg.it ra.it rc.it re.it ri.it rm.it ro.it sa.it ss.it sv.it si.it sr.it so.it ta.it te.it tr.it to.it tp.it tn.it tv.it ts.it ud.it va.it ve.it vb.it vc.it vr.it vv.it vi.it vt.it agrigento.it alessandria.it ancona.it aosta.it bari.it bologna.it bolzano.it brescia.it brindisi.it cagliari.it catania.it catanzaro.it cesena.it firenze.it genova.it lecce.it livorno.it milano.it modena.it napoli.it novara.it padova.it palermo.it parma.it perugia.it pescara.it piacenza.it pisa.it potenza.it ravenna.it reggio.it roma.it salerno.it sassari.it siena.it taranto.it terni.it torino.it trento.it treviso.it trieste.it udine.it varese.it venezia.it verona.it vicenza.it viterbo.it bergamo.it biella.it cuneo.it cremona.it como.it ferrara.it foggia.it forli.it grosseto.it latina.it lucca.it mantova.it massa.it messina.it monza.it nuoro.it oristano.it pavia.it pordenone.it prato.it ragusa.it rieti.it rimini.it rovigo.it savona.it siracusa.it sondrio.it teramo.it trapani.it asti.it avellino.it belluno.it benevento.it caltanissetta.it campobasso.it caserta.it chieti.it cosenza.it crotone.it enna.it fermo.it frosinone.it gorizia.it imperia.it isernia.it laquila.it lodi.it macerata.it matera.it pesaro.it pistoia.it vercelli.it abruzzo.it basilicata.it calabria.it campania.it emilia-romagna.it friuli-venezia-giulia.it lazio.it liguria.it lombardia.it marche.it molise.it piemonte.it puglia.it sardegna.it sicilia.it toscana.it trentino-alto-adige.it umbria.it valle-d-aosta.it veneto.it"

    /** Hosting/servizi dove ogni sottodominio è di un soggetto diverso. */
    private val hosting = (
        "github.io gitlab.io pages.dev workers.dev r2.dev web.app firebaseapp.com netlify.app vercel.app " +
            "herokuapp.com appspot.com blogspot.com blogspot.it wordpress.com weebly.com wixsite.com " +
            "000webhostapp.com glitch.me duckdns.org ngrok-free.app ngrok.io trycloudflare.com azurewebsites.net " +
            "cloudfront.net s3.amazonaws.com myshopify.com webflow.io framer.app carrd.co sites.google.com " +
            "godaddysites.com square.site ddns.net no-ip.org hopto.org zapto.org serveo.net lovable.app " +
            "replit.app repl.co onrender.com fly.dev railway.app"
        ).split(' ').toHashSet()

    private fun isSuffix(s: String): Boolean =
        s in multi || s in hosting ||
            (!s.contains('.') && (s in gtlds || (s.length == 2 && s.all { it in 'a'..'z' }) || s.startsWith("xn--")))

    fun isKnownTld(tld: String) = isSuffix(tld)

    /** Suffisso pubblico noto più lungo di [host] (ASCII minuscolo). */
    fun suffixOf(host: String): String {
        val labels = host.split('.')
        for (i in labels.indices) {
            val cand = labels.subList(i, labels.size).joinToString(".")
            if (isSuffix(cand)) return cand
        }
        return labels.last()
    }

    /** eTLD+1; se l'host è già un suffisso o un IP, lo restituisce invariato. */
    fun registrable(host: String): String {
        if (Hosts.isIp(host)) return host
        val suffix = suffixOf(host)
        if (host == suffix) return host
        val rest = host.removeSuffix(".$suffix")
        return rest.substringAfterLast('.') + "." + suffix
    }
}
