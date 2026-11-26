#!/usr/bin/env python3
"""Submit three images with page numbers arriving as 3, 1, 2."""

import argparse
import getpass
import json
import mimetypes
import os
import re
import uuid
from http.cookiejar import CookieJar
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPCookieProcessor, Request, build_opener


def main():
    parser = argparse.ArgumentParser(
        description="Send page parts in the order 3, 1, 2 and print the submission ID."
    )
    parser.add_argument("image_for_page_3")
    parser.add_argument("image_for_page_1")
    parser.add_argument("image_for_page_2")
    args = parser.parse_args()

    base_url = os.environ.get("APP_URL", "http://localhost:8080").rstrip("/")
    password = os.environ.get("DEMO_SCANNER_PASSWORD") or getpass.getpass("Scanner password: ")
    opener = build_opener(HTTPCookieProcessor(CookieJar()))

    login_page = opener.open(base_url + "/upload.html")
    login_html = login_page.read().decode()
    token_match = re.search(r'<input[^>]*name="_csrf"[^>]*value="([^"]+)"', login_html)
    if token_match is None:
        raise SystemExit("Could not find the login form CSRF token.")

    credentials = urlencode({
        "username": "scanner",
        "password": password,
        "_csrf": token_match.group(1),
    }).encode()
    signed_in = opener.open(Request(base_url + "/login", data=credentials))
    upload_page = signed_in.read().decode()
    if urlparse(signed_in.geturl()).path != "/upload.html" or "Pages added" not in upload_page:
        raise SystemExit("Scanner login failed.")

    csrf = json.loads(opener.open(base_url + "/csrf").read())
    boundary = "----PageOrder" + uuid.uuid4().hex
    arrivals = [
        (3, args.image_for_page_3),
        (1, args.image_for_page_1),
        (2, args.image_for_page_2),
    ]
    manifest = []
    file_parts = []

    for page_number, path in arrivals:
        if not os.path.isfile(path):
            raise SystemExit(f"Image file not found: {path}")
        file_part = "image-" + uuid.uuid4().hex
        manifest.append({"filePart": file_part, "pageNumber": page_number})
        content_type = mimetypes.guess_type(path)[0] or "application/octet-stream"
        with open(path, "rb") as image_file:
            contents = image_file.read()
        file_parts.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{file_part}"; filename="upload"\r\n'
            f"Content-Type: {content_type}\r\n\r\n".encode()
            + contents
            + b"\r\n"
        )

    body = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="manifest"; filename="manifest.json"\r\n'
        "Content-Type: application/json\r\n\r\n"
        + json.dumps({"pages": manifest})
        + "\r\n"
    ).encode() + b"".join(file_parts) + f"--{boundary}--\r\n".encode()

    request = Request(
        base_url + "/api/submissions",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            csrf["headerName"]: csrf["token"],
        },
    )
    response = json.loads(opener.open(request).read())
    print(response["submissionId"])


if __name__ == "__main__":
    main()
