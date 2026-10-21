"""Test della pipeline: python -I -m unittest discover -s tools/blocklist  (solo stdlib)."""
import os, sys, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_blocklist as b

PROTECTED = {"google.com", "poste.it", "youtube.com", "amazon.it"}


class Normalize(unittest.TestCase):
    def test_host_extraction(self):
        self.assertEqual(b.host_of("http://222.138.151.123:45755/bin.sh"), "222.138.151.123")
        self.assertEqual(b.host_of("https://User:pw@Evil.COM:8443/a?b=c#d"), "evil.com")
        self.assertEqual(b.host_of("evil.com."), "evil.com")
        self.assertEqual(b.host_of("www.evil.com/login"), "www.evil.com")
        self.assertEqual(b.host_of("hxxp://evil.com/x") , "evil.com")  # schema qualunque
        self.assertIsNone(b.host_of("# commento"))
        self.assertIsNone(b.host_of("localhost"))
        self.assertIsNone(b.host_of("http://[::1]/x"))
        self.assertIsNone(b.host_of("http://999.1.1.1/x"))
        self.assertIsNone(b.host_of("http://exa mple.com/"))

    def test_punycode(self):
        self.assertEqual(b.host_of("https://bücher.example.com/x"), "xn--bcher-kva.example.com")
        self.assertEqual(b.host_of("https://xn--bcher-kva.example.com"), "xn--bcher-kva.example.com")
        self.assertEqual(b.host_of("http://пример.рф/"), "xn--e1afmkfd.xn--p1ai")
        self.assertIsNone(b.host_of("http://" + "a" * 70 + ".com/"))

    def test_hash_is_sha256_prefix(self):
        import hashlib
        self.assertEqual(b.host_hash("evil.com"), hashlib.sha256(b"evil.com").digest()[:6])


class Suffixes(unittest.TestCase):
    def test_registrable_matches_engine_rules(self):
        self.assertEqual(b.registrable("a.b.evil.com"), "evil.com")
        self.assertEqual(b.registrable("shop.evil.co.uk"), "evil.co.uk")
        self.assertEqual(b.registrable("evil.github.io"), "evil.github.io")  # hosting condiviso: tenant
        self.assertEqual(b.registrable("x.evil.github.io"), "evil.github.io")
        self.assertEqual(b.registrable("comune.bologna.it"), "comune.bologna.it")

    def test_hosting_list_includes_engine_list(self):
        for h in ("github.io", "pages.dev", "web.app", "firebaseapp.com", "netlify.app", "vercel.app", "blogspot.com",
                  "sites.google.com", "weebly.com", "wixsite.com"):
            self.assertIn(h, b.HOSTING, h)


class Filters(unittest.TestCase):
    def run_filter(self, hosts):
        return b.filter_hosts(set(hosts), PROTECTED)[0]

    def test_popular_and_subdomains_dropped(self):
        kept = self.run_filter(["google.com", "mail.google.com", "www.poste.it", "login.poste.it.evil.com", "amazon.it"])
        self.assertEqual(kept, {"login.poste.it.evil.com"})

    def test_shared_hosting_only_full_tenant_host(self):
        kept = self.run_filter(["github.io", "evil.github.io", "x.evil.github.io", "web.app", "evil.web.app",
                                "sites.google.com", "pages.dev", "evil.pages.dev", "co.uk"])
        self.assertEqual(kept, {"evil.github.io", "x.evil.github.io", "evil.web.app", "evil.pages.dev"})

    def test_tenant_named_like_a_popular_domain_is_still_just_a_tenant(self):
        # google.com.evil.github.io non e' google.com
        self.assertIn("google.com.evil.github.io", self.run_filter(["google.com.evil.github.io"]))

    def test_private_ips_dropped_public_kept(self):
        kept = self.run_filter(["10.0.0.5", "192.168.1.1", "127.0.0.1", "172.16.0.9", "169.254.1.1", "8.8.4.4", "203.0.113.9"])
        self.assertEqual(kept, {"8.8.4.4"})  # 203.0.113.0/24 e' documentazione: non instradabile

    def test_institutional_dropped(self):
        kept = self.run_filter(["www.inps.gov.it", "comune.bologna.it", "evil.com"])
        self.assertEqual(kept, {"evil.com"})

    def test_collapse_children_dedup(self):
        hosts = {"evil.com", "a.evil.com", "b.a.evil.com", "other.com", "x.tenant.github.io", "tenant.github.io", "1.2.3.4"}
        self.assertEqual(b.collapse_children(hosts), {"evil.com", "other.com", "tenant.github.io", "1.2.3.4"})
        # su hosting condiviso il padre nudo non e' mai considerato
        self.assertEqual(b.collapse_children({"a.github.io", "b.github.io"}), {"a.github.io", "b.github.io"})

    def test_pack_sorted_unique(self):
        hs, blob = b.pack(["evil.com", "evil.com", "x.org"])
        self.assertEqual(len(hs), 2)
        self.assertEqual(hs, sorted(hs))
        self.assertEqual(len(blob), 12)


if __name__ == "__main__":
    unittest.main()
