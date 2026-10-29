@echo off
cd /d "%~dp0"
python -c "import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)"
if errorlevel 1 (
    echo Python 3.12+ is required.
    exit /b 1
)
if not exist .venv python -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install -r requirements-dev.txt
if not exist .env copy .env.example .env
echo Setup complete. Start with: run.bat
