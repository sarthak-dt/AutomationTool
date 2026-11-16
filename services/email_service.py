"""Phase 2 email delivery implementation; currently dormant in the app.

It sends through Zoho first and can fall back to Outlook when enabled.
"""
from __future__ import annotations

from dataclasses import dataclass
from email.message import EmailMessage
from email.utils import parseaddr
import smtplib
import ssl
from typing import Any


@dataclass(frozen=True)
class SmtpProvider:
    name: str
    host: str
    port: int
    username: str
    password: str
    sender: str = ""

    @property
    def configured(self) -> bool:
        return bool(self.host and self.port and self.username and self.password)


def providers_from_config(config: Any) -> list[SmtpProvider]:
    """Return configured providers in preference order: Zoho, then Outlook."""
    providers = [
        SmtpProvider("Zoho", config.email_primary_host, config.email_primary_port,
                     config.email_primary_username, config.email_primary_password,
                     config.email_primary_from),
        SmtpProvider("Outlook", config.email_fallback_host, config.email_fallback_port,
                     config.email_fallback_username, config.email_fallback_password,
                     config.email_fallback_from),
    ]
    return [provider for provider in providers if provider.configured]


def send_report_email(
    config: Any, recipient: str, subject: str, body: str,
    attachment_name: str, attachment: bytes,
) -> str:
    """Send the report using Zoho first, falling back to Outlook on delivery failure.

    Returns the provider name. Raises a sanitized RuntimeError if no account is
    configured or all configured providers fail.
    """
    address = parseaddr(recipient.strip())[1]
    if not address or "@" not in address or address.startswith("@") or address.endswith("@"):
        raise ValueError("Enter a valid recipient email address.")
    providers = providers_from_config(config)
    if not providers:
        raise RuntimeError("Email is not configured. Set the Zoho SMTP settings, and optionally Outlook fallback settings, in .env.")

    message = EmailMessage()
    message["To"] = address
    message["Subject"] = subject
    message.set_content(body)
    message.add_attachment(attachment, maintype="application", subtype="zip", filename=attachment_name)
    failures: list[str] = []
    for provider in providers:
        sender = provider.sender or provider.username
        try:
            with smtplib.SMTP(provider.host, provider.port, timeout=20) as smtp:
                smtp.ehlo()
                smtp.starttls(context=ssl.create_default_context())
                smtp.ehlo()
                smtp.login(provider.username, provider.password)
                smtp.send_message(message, from_addr=sender, to_addrs=[address])
            return provider.name
        except (OSError, smtplib.SMTPException) as exc:
            failures.append(f"{provider.name}: {type(exc).__name__}")
    raise RuntimeError("Could not send through configured email providers (" + "; ".join(failures) + "). Check SMTP settings and account access.")
