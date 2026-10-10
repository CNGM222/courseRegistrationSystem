"""Run against the disposable demo profile on 127.0.0.1:18080."""
import http.cookiejar
import json
import urllib.error
import urllib.request


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )
        self.csrf = None

    def call(self, path, method="GET", body=None, version=None, expected=200):
        if method != "GET" and self.csrf is None:
            self.csrf = self.call("/auth/csrf")[0]
        headers = {"Accept": "application/json"}
        if method != "GET":
            headers[self.csrf["headerName"]] = self.csrf["token"]
        if version is not None:
            headers["If-Match"] = f'"{version}"'
        raw = None
        if body is not None:
            raw = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            "http://127.0.0.1:18080/api/v1" + path,
            data=raw, headers=headers, method=method,
        )
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read().decode()
            result = json.loads(content) if content else {}
            assert response.status == expected, (path, response.status, result)
            if expected < 300:
                if expected != 204:
                    assert result.get("requestId"), (path, "missing requestId")
                return result.get("data"), response.headers
            assert result.get("requestId") and result.get("code"), result
            return result, response.headers

    def login(self, username):
        self.call("/auth/login", "POST", {
            "username": username, "password": "demo-only-2026",
        })
        self.csrf = None


student, professor, registrar = Client(), Client(), Client()
student.call("/semesters", expected=401)
student.login("student.demo")
professor.login("professor.demo")
registrar.login("registrar.demo")
semester = student.call("/semesters")[0][0]["id"]
catalog = student.call(f"/semesters/{semester}/offerings")[0]
assert catalog["total"] == 7 and len(catalog["departments"]) == 4
offerings = {item["courseCode"]: item["id"] for item in catalog["items"]}
plan = professor.call(f"/professors/me/teaching/{semester}")[0]
selected, headers = professor.call(
    f"/professors/me/teaching/{semester}", "PUT",
    {"offeringIds": list(offerings.values())}, plan["version"],
)
assert selected["version"] > plan["version"]
assert headers["ETag"] == f'"{selected["version"]}"'
primary = [offerings[code] for code in ("CS101", "CS102", "MA101", "EN101")]
alternate = [offerings[code] for code in ("PH101", "CS103")]
path = f"/students/me/schedules/{semester}"
student.call(path, expected=404)
draft = student.call(path + "/draft", "PUT", {
    "primaryOfferingIds": primary, "alternateOfferingIds": alternate,
})[0]
assert not draft["enrollments"]
assert student.call(path + "/validate", "POST", {
    "draftRevision": draft["draftRevision"],
})[0]["valid"]
student.call(path + "/submit", "POST", {
    "draftRevision": draft["draftRevision"],
}, expected=428)
submitted = student.call(path + "/submit", "POST", {
    "draftRevision": draft["draftRevision"],
}, draft["version"])[0]
assert len(submitted["enrollments"]) == 4
student.call(path + "/draft", "PUT", {
    "primaryOfferingIds": [], "alternateOfferingIds": [],
}, draft["version"], expected=412)
draft = student.call(path + "/draft", "PUT", {
    "primaryOfferingIds": [offerings["CS201"]], "alternateOfferingIds": [],
}, submitted["version"])[0]
issues = student.call(path + "/validate", "POST", {
    "draftRevision": draft["draftRevision"],
})[0]
assert not issues["valid"] and any(i["code"] == "PREREQUISITE_NOT_MET" for i in issues["issues"])
student.call("/registrar/students", expected=403)
student.call("/unknown-endpoint", expected=404)
professor.call(f"/professors/me/offerings/{primary[0]}/roster", expected=409)
person = registrar.call("/registrar/students", "POST", {
    "username": "smoke.synthetic", "name": "接口演示学生",
    "birthDate": "2005-01-01", "status": "ACTIVE",
    "socialSecurityNumber": "123456789", "graduationDate": None,
})[0]
assert person["ssnMasked"] == "***-**-6789"
people_page = registrar.call("/registrar/students?keyword=smoke.synthetic")[0]
assert people_page["total"] == 1
registrar.call(f'/registrar/students/{person["id"]}', "DELETE", version=person["version"], expected=204)
registrar.call(f'/registrar/students/{person["id"]}', expected=404)
registrar.call(f"/registrar/semesters/{semester}/close", "POST", expected=409)
student.call(path, "DELETE", version=draft["version"], expected=204)
student.call("/auth/logout", "POST", expected=204)
student.call("/auth/me", expected=401)
print("HTTP demo smoke passed: three roles, JDBC sessions, CSRF, ETag, teaching, registration, prerequisites, people, authorization, logout.")
